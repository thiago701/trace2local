#!/bin/sh
# Provisionamento do cenário Lambda + DynamoDB + SQS no LocalStack (compose).
# Roda dentro do container amazon/aws-cli; o fat jar está em /bundle (read-only).
set -e

echo "[init] aguardando o LocalStack responder..."
until aws --endpoint-url=http://localstack:4566 dynamodb list-tables >/dev/null 2>&1; do
  sleep 1
done

echo "[init] tabela DynamoDB (orders)..."
aws --endpoint-url=http://localstack:4566 dynamodb create-table \
  --table-name orders \
  --attribute-definitions AttributeName=pk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST || true

echo "[init] fila SQS (orders-queue)..."
aws --endpoint-url=http://localstack:4566 sqs create-queue \
  --queue-name orders-queue || true

echo "[init] função Lambda order-processor (runtime java21)..."
# TRACEVANTA_STATION_ENDPOINT aponta para o Station publicado no host (19877):
# de dentro do emulador Lambda, host.docker.internal alcança a máquina host.
if ! aws --endpoint-url=http://localstack:4566 lambda create-function \
  --function-name order-processor \
  --runtime java21 \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --handler tech.neural7.tracevanta.examples.lambda.OrderProcessor::handleRequest \
  --zip-file fileb:///bundle/lambda-sqs-bundle.jar \
  --timeout 30 \
  --environment "Variables={TRACEVANTA_STATION_ENDPOINT=http://host.docker.internal:19877,TRACEVANTA_STATION_TOKEN=devtoken}"; then
  echo "[init] função já existia — atualizando o código e o ambiente..."
  aws --endpoint-url=http://localstack:4566 lambda update-function-code \
    --function-name order-processor \
    --zip-file fileb:///bundle/lambda-sqs-bundle.jar
  aws --endpoint-url=http://localstack:4566 lambda update-function-configuration \
    --function-name order-processor \
    --environment "Variables={TRACEVANTA_STATION_ENDPOINT=http://host.docker.internal:19877,TRACEVANTA_STATION_TOKEN=devtoken}"
fi

echo "[init] invocação de fumaça (primeira chamada puxa a imagem java:21 e extrai o código — pode demorar)..."
# o gateway do LocalStack espera o Payload base64 (comportamento verificado no 4.2)
PAYLOAD_B64=$(printf '%s' '{"orderId":"ORDER-C1","customerId":"C-COMPOSE","total":"99.90"}' | base64 -w0)
OK=0
for attempt in 1 2 3 4 5 6; do
  if aws --endpoint-url=http://localstack:4566 lambda invoke \
    --function-name order-processor \
    --payload "$PAYLOAD_B64" \
    /tmp/out.json 2>/tmp/err.json; then
    OK=1
    break
  fi
  echo "[init] tentativa $attempt falhou ($(head -c 200 /tmp/err.json)) — aguardando o emulador aquecer..."
  sleep 15
done
if [ "$OK" != "1" ]; then
  echo "[init] FATAL: a função não respondeu após 6 tentativas"
  exit 1
fi
echo "[init] resposta da função:"
cat /tmp/out.json
echo
echo "[init] cenário pronto → UI do Station em http://localhost:19877/tracevanta"
