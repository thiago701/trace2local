# payment-service — demo de storytelling em PAGAMENTOS (Pix)

Demo de **domínio diferente** dos exemplos de pedidos: cria Pix com guarda de
idempotência, recusa duplicados sem efeito colateral e confirma com update
read-back (before+after EXACT) — para validar a **clareza da narrativa** do
TraceVanta para PO, dev e QA.

## Rodar

```bash
# 1) LocalStack com a tabela `payments` (qualquer LocalStack 3/4)
aws --endpoint-url=http://localhost:4566 dynamodb create-table \
  --table-name payments \
  --attribute-definitions AttributeName=pk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH --billing-mode PAY_PER_REQUEST

# 2) a app (UI do TraceVanta em :9876, app em :8080)
LOCALSTACK_ENDPOINT=http://localhost:4567 java -jar target/payment-service-0.1.0-SNAPSHOT.jar
```

## Fluxos

```bash
curl -X POST localhost:8080/pix -H "Content-Type: application/json" \
  -d '{"key":"PIX-1","payer":"Ana","amount":99.90}'       # cria (201)
curl -X POST localhost:8080/pix -H "Content-Type: application/json" \
  -d '{"key":"PIX-1","payer":"Ana","amount":99.90}'       # duplicado: recusado pela guarda
curl -X POST localhost:8080/pix/PIX-1/confirm             # confirma (update read-back)
curl localhost:8080/pix/PIX-1                             # consulta (leitura)
```

Na UI (`http://localhost:9876/tracevanta`): aba **STORY** mostra a narrativa em
linguagem de negócio (glossário deste projeto) e o toggle **NOTAS** anota cada
nó no canvas.
