#!/bin/sh
# Provisionamento + jornada REAL do cenário Lambda + DynamoDB + SQS + CloudWatch Logs
# no LocalStack (compose). Roda dentro do container amazon/aws-cli; o fat jar está
# em /bundle (read-only). As funções falam com o Station pela rede do compose.
set -e
EP=http://localstack:4566
STATION_ENV="TRACE2LOCAL_STATION_ENDPOINT=http://trace2local-station:19877,TRACE2LOCAL_STATION_TOKEN=devtoken"
aws_ls() { aws --endpoint-url=$EP "$@"; }

echo "[init] aguardando o LocalStack responder..."
until aws_ls dynamodb list-tables >/dev/null 2>&1; do
  sleep 1
done

for t in orders idempotency payments; do
  echo "[init] tabela DynamoDB ($t)..."
  aws_ls dynamodb create-table --table-name $t \
    --attribute-definitions AttributeName=pk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST >/dev/null || true
done

echo "[init] fila SQS (orders-queue)..."
QUEUE_URL=$(aws_ls sqs create-queue --queue-name orders-queue --query QueueUrl --output text)
QUEUE_ARN=$(aws_ls sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)

deploy() { # nome handler
  echo "[init] função Lambda $1 (java21) → $2"
  if ! aws_ls lambda create-function --function-name "$1" --runtime java21 \
      --role arn:aws:iam::000000000000:role/lambda-role \
      --handler "$2" --zip-file fileb:///bundle/lambda-sqs-bundle.jar \
      --timeout 30 --memory-size 512 \
      --environment "Variables={$STATION_ENV}" >/dev/null; then
    echo "[init] $1 já existia — atualizando código e ambiente..."
    aws_ls lambda update-function-code --function-name "$1" --zip-file fileb:///bundle/lambda-sqs-bundle.jar >/dev/null
    aws_ls lambda wait function-updated --function-name "$1" || true
    aws_ls lambda update-function-configuration --function-name "$1" --environment "Variables={$STATION_ENV}" >/dev/null
  fi
  aws_ls lambda wait function-active-v2 --function-name "$1" || true
}
deploy order-processor tech.neural7.trace2local.examples.lambda.OrderProcessor::handleRequest
deploy order-billing tech.neural7.trace2local.examples.lambda.OrderBillingSqsHandler::handleRequest
deploy idempotent-processor tech.neural7.trace2local.examples.lambda.IdempotentProcessor::handleRequest

echo "[init] event source mapping orders-queue → order-billing (consumidor real)..."
if [ -z "$(aws_ls lambda list-event-source-mappings --function-name order-billing --query 'EventSourceMappings[0].UUID' --output text | grep -v None)" ]; then
  aws_ls lambda create-event-source-mapping --function-name order-billing \
    --event-source-arn "$QUEUE_ARN" --batch-size 1 >/dev/null
fi

invoke() { # função payload-json rótulo
  # o gateway do LocalStack espera o Payload base64 (comportamento verificado no 4.2)
  P=$(printf '%s' "$2" | base64 -w0)
  for attempt in 1 2 3 4 5 6; do
    if aws_ls lambda invoke --function-name "$1" --payload "$P" /tmp/out.json >/tmp/meta.json 2>/tmp/err.json; then
      echo "[init] $3 → $(cat /tmp/out.json)"
      return 0
    fi
    echo "[init] $3: tentativa $attempt falhou ($(head -c 160 /tmp/err.json)) — aguardando o emulador aquecer..."
    sleep 15
  done
  echo "[init] FATAL: $1 não respondeu"; exit 1
}

echo "[init] jornadas (a primeira chamada puxa a imagem java:21 — pode demorar)..."
invoke order-processor '{"orderId":"ORDER-C1","customerId":"C-ANA","total":"99.90"}' "J1 pedido feliz"
invoke order-processor '{"orderId":"ORDER-C2","customerId":"C-BRUNO","total":"249.00"}' "J2 pedido feliz"
invoke order-processor '{"orderId":"ORDER-C3","customerId":"C-CARLA","total":"9999.00","fail":"true"}' "J3 recusa por limite de crédito (FunctionError esperado)"
invoke idempotent-processor '{"idempotencyKey":"PAY-777","payload":"{\"valor\":120}"}' "J4 idempotência — 1ª entrega"
invoke idempotent-processor '{"idempotencyKey":"PAY-777","payload":"{\"valor\":120}"}' "J5 idempotência — reentrega"
invoke order-processor '{"orderId":"ORDER-C4","customerId":"C-DIEGO","total":"75.50"}' "J6 pedido feliz"

echo "[init] aguardando o consumidor order-billing drenar a fila..."
for i in $(seq 1 30); do
  N=$(aws_ls sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
      --query 'sum([to_number(Attributes.ApproximateNumberOfMessages), to_number(Attributes.ApproximateNumberOfMessagesNotVisible)])' --output text 2>/dev/null || echo 0)
  [ "$N" = "0" ] && break
  sleep 2
done
echo "[init] cenário pronto → UI do Station em http://localhost:19877/trace2local"
