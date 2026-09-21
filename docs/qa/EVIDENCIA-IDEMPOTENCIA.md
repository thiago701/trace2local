# EVIDÊNCIAS QA — Monitoramento de Idempotência (E2E)

> Cenário: handler Lambda-style com **guarda de idempotência** (escrita condicional
> `attribute_not_exists(pk)` no DynamoDB + nó BUSINESS `IdempotencyGuard`),
> monitorado pelo TraceVanta. Data: 2026-09-20 · LocalStack 4.2 real ·
> Station com token Bearer.

## O que foi validado

| # | Verificação | Resultado |
|---|---|---|
| 1 | 1ª chamada (chave nova) cria o item | ✅ `{"status":"created"}` |
| 2 | 2ª chamada (MESMA chave) é recusada pela guarda | ✅ `{"status":"duplicate_ignored"}` |
| 3 | **Sem efeito colateral**: 3 chamadas → **2 itens** no DynamoDB | ✅ `Count: 2` (IT e compose) |
| 4 | Árvore da criação: `LAMBDA → IdempotencyGuard (BUSINESS, OK) → DynamoDB (CREATE/EXACT)` | ✅ `COMPLETED` |
| 5 | Árvore do duplicado: `LAMBDA (OK) → IdempotencyGuard (OK) → DynamoDB (ERROR)` | ✅ nó vermelho com `ConditionalCheckFailedException` |
| 6 | **Sem delta na tentativa duplicada** — a prova visual de que nada foi escrito | ✅ `mutation` ausente no nó ERROR |
| 7 | Erro legível na árvore: ingest OTLP agora extrai `exception.type/message/stacktrace` dos eventos do span | ✅ tipo + mensagem completos no inspector |
| 8 | Consistência UI↔API | ✅ `UI=3 nós, API=3 nós → labels idênticos` |

**Semântica documentada**: execução com nó ERROR fecha `FAILED` (invariante do
assembler) — para o monitor de idempotência é o sinal correto de "escrita
recusada", mesmo com a invocação terminando OK (a recusa É o comportamento
esperado; a árvore separa os dois: raiz e guarda OK, escrita rejeitada).

## Leitura do monitor (o que a árvore responde)

| Pergunta do negócio | Resposta na árvore |
|---|---|
| Este request criou algo? | nó DynamoDB `CREATE/EXACT` com delta |
| Este request era um duplicado? | nó DynamoDB `ERROR` + `ConditionalCheckFailedException` **sem delta** |
| A guarda funcionou? | nó BUSINESS `IdempotencyGuard` `OK` nas duas chamadas |
| Houve efeito colateral indevido? | não — contagem no banco permanece 2 após 3 chamadas |

## Artefatos de evidência

| Artefato | Caminho |
|---|---|
| Árvore da criação (IT) | [`evidence-idempotency-created.json`](evidence-idempotency-created.json) |
| Árvore do duplicado (IT) | [`evidence-idempotency-duplicate.json`](evidence-idempotency-duplicate.json) |
| Árvore do duplicado (compose, UI) | [`evidence-idempotency-compose.json`](evidence-idempotency-compose.json) |
| Tela da árvore do duplicado | [`screenshots/13-idempotency-duplicate-tree.png`](screenshots/13-idempotency-duplicate-tree.png) |
| Tela do inspector do nó vermelho | [`screenshots/14-idempotency-inspector-guard.png`](screenshots/14-idempotency-inspector-guard.png) |

## Como reproduzir

```bash
# 1) E2E automatizado (Testcontainers + Station em processo, porta 19878)
./mvnw -Pit -pl examples/lambda-sqs verify    # IdempotencyJourneyIT

# 2) Demo visual no compose (Station :19877 com token)
java -cp examples/lambda-sqs/target/lambda-sqs-bundle.jar;<m2>/aws-lambda-java-core-1.4.0.jar \
  -Dlocalstack.endpoint=http://localhost:4567 \
  -Dtracevanta.station.endpoint=http://127.0.0.1:19877 \
  -Dtracevanta.station.token=devtoken \
  tech.neural7.tracevanta.examples.lambda.IdempotencyDemoRun
node scripts/screenshots/capture-idempotency.mjs
```

## Melhoria aplicada no loop

- **Ingest OTLP agora lê eventos `exception`** (`OtlpTraceReceiver`): antes, a
  mensagem de erro de spans instrumentados por terceiros (AWS SDK etc.) chegava
  vazia — a árvore ficava vermelha SEM dizer por quê. Agora `exception.type`,
  `exception.message` e `exception.stacktrace` viram o `ErrorInfo` do nó
  (teste unitário em `OtlpTraceReceiverTest.parsesExceptionEventsIntoErrorInfo`).
