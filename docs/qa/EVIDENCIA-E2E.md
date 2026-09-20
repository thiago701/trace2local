# EVIDÊNCIAS QA — E2E TraceVanta — cenário completo LocalStack

Data: 2026-09-20T01:31:25.018512900Z
Ambiente: localstack/localstack:4.2 | container 9f6dd725550250e0dfaef60eb49fe9af42c96afdfe25f0bc3d3ac9b86b409c33 | Java 25.0.3
Infra provisionada: tabela `orders` + tópico SNS `order-events` + fila SQS `billing-queue` (fanout SNS→SQS) + consumidor BillingConsumer na app

## JC-1 + JC-3 — POST /orders disparado pela UI, consumido pela fila na MESMA árvore
App :18089 | TraceVanta :55328
- disparo → `execution.started` no SSE: **363 ms** (NFR-4: < 1 s)
- execução **TV-00001** concluída em **1552 ms** | status=COMPLETED | nós=8 | profundidade=7
- delta DynamoDB **EXACT** (E7): before={} item novo, after={pk=ORDER#ecfe44ad, total=250.0}
- JC-3 (E8): SNS→SQS→BillOrder correlacionado na MESMA árvore (SQS receive e BillOrder continuam o trace via AWSTraceHeader/Parent) | GetItem READ_ONLY
- redaction (E11): payload do nó HTTP contém `[TRACEVANTA_REDACTED]` e NÃO contém a senha
- export .tvtrace (JC-4): manifest format=tvtrace, version 0.1.0
- /api/health: status=ok dropped=0 internalErrors=0 connectedClients=1
- evidência JSON: docs/qa/evidence-jc1-jc3-execution.json
- jornada completa (disparo → consumidor → árvore pronta) **1552 ms**

## JC-2 — POST /orders/{id}/confirm (condição DynamoDB + evento SNS)
- 1º confirm: status=COMPLETED | UPDATE EXACT after.status=CONFIRMED, before=null (declarado, I3)
- consumidor na MESMA árvore do confirm: SQS billing-queue + BillOrder + UPDATE EXACT after.status=BILLED
- evidência JSON: docs/qa/evidence-jc2-confirm-ok.json
- 2º confirm: status=**FAILED** | nó DynamoDB com ConditionalCheckFailedException + stack recortado
- evidência JSON: docs/qa/evidence-jc2-conflict.json