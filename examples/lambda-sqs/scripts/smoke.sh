#!/bin/sh
# Invocação de fumaça da função order-processor (reuso do cenário do compose).
# Uso: docker run --rm --network <rede-do-compose> -e AWS_... -v <bundle>:... amazon/aws-cli:2.22.0 /bin/sh /smoke.sh
set -e
ENDPOINT="${LOCALSTACK_ENDPOINT:-http://localstack:4566}"
PAYLOAD_B64="eyJvcmRlcklkIjoiT1JERVItQzEiLCJjdXN0b21lcklkIjoiQy1DT01QT1NFIiwidG90YWwiOiI5OS45MCJ9"
OK=0
for attempt in 1 2 3 4 5 6; do
  if aws --endpoint-url="$ENDPOINT" lambda invoke \
    --function-name order-processor \
    --payload "$PAYLOAD_B64" \
    /tmp/out.json 2>/tmp/err.json; then
    OK=1
    break
  fi
  echo "[smoke] tentativa $attempt falhou — aguardando o emulador aquecer..."
  head -c 200 /tmp/err.json || true
  sleep 15
done
if [ "$OK" != "1" ]; then
  echo "[smoke] FATAL: a função não respondeu após 6 tentativas"
  exit 1
fi
echo "[smoke] resposta da função:"
cat /tmp/out.json
echo
