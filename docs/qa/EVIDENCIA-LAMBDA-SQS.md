# EVIDÊNCIAS QA — Projeto de teste/validação lambda-sqs (Lambda + DynamoDB + SQS no LocalStack)

> Cenário: **AWS Lambda (runtime java21) + DynamoDB + SQS no LocalStack 4.2, configurado com a lib Trace2Local** (modo Companion, ADR-002).
> Data: 2026-09-19/20 · Build: JDK 25 / bytecode 21 · LocalStack 4.2 · Docker Desktop (Windows 11).

## O que foi validado

| # | Verificação | Resultado |
|---|---|---|
| 1 | Handler instrumentado roda no **emulador Lambda REAL** do LocalStack (`create-function`/`invoke`, runtime java21) | ✅ `StatusCode: 200`, `{"status":"ok","orderId":"ORDER-C1"}` |
| 2 | Efeito real no **DynamoDB** (`orders`) | ✅ item com `customerId`/`total` gravados |
| 3 | Mensagem real na **SQS** (`orders-queue`) | ✅ body `{"orderId":...}` |
| 4 | **J1** — árvore no Station: raiz `LAMBDA` → `DYNAMODB` (delta EXACT) + `SQS` | ✅ 3 nós, `COMPLETED`, `warnings=0` |
| 5 | **J2** — erro do handler → execução **FAILED** com raiz vermelha, mensagem da exceção visível e ramo DynamoDB OK (sucesso parcial) | ✅ 2 nós, `FAILED`, erro "ordem recusada … limite de crédito" |
| 6 | **J3** — consumidor SQS continua a MESMA árvore via `AWSTraceHeader` (`remoteParentOf`) | ✅ 5 nós: `LAMBDA → {DYNAMODB, SQS → LAMBDA → DYNAMODB (UPDATE/EXACT)}` |
| 7 | Trigger/execution id parseados no ingest OTLP; identidade de execução ESTÁVEL | ✅ `trigger=LAMBDA_EVENT`, `executionId` = request id do produtor |
| 8 | **Duração de execução positiva** (janela dos spans, não ordem de eventos) | ✅ 75 ms / 74 ms / 2329 ms (antes: **-255 ms** — bug corrigido) |
| 9 | **Consistência UI↔API**: labels do canvas comparados nó a nó com a API REST | ✅ 3 árvores verificadas (`UI=n nós, API=n nós → labels idênticos`) |
| 10 | Repetibilidade: 2ª invocação no emulador quente gera nova execução | ✅ múltiplas execuções `LAMBDA_EVENT` |
| 11 | **Token de ingest (ADR-007/§8.1)**: Station com `TRACE2LOCAL_STATION_TOKEN` rejeita OTLP sem Bearer (401), aceita com Bearer — Lambda emulada e DemoRun enviam automaticamente; delta EXACT chega pelo canal de mutação protegido | ✅ 401 sem token · execução COMPLETED com `CREATE/EXACT` via ingest autenticado |

## Fluxo validado

```
aws lambda invoke (LocalStack, runtime java21)
        │
        ▼
OrderProcessor (Trace2LocalLambdaHandler — span raiz SERVER com faas.name → LAMBDA)
        │  Trace2LocalAws.instrument(DynamoDbClient)  → PutItem (delta EXACT)
        │  span PRODUCER manual + AWSTraceHeader     → SendMessage SQS
        ▼
flush síncrono (ADR-002/§4.2): OTLP → /v1/traces · mutações → /t2lingest/v1/mutations
        │
        ▼
Station (:19877) — J1: LAMBDA → {DYNAMODB (CREATE/EXACT), SQS}
                  J2: LAMBDA (ERROR) → DYNAMODB (OK)          ← sucesso parcial
                  J3: LAMBDA → {DYNAMODB, SQS → LAMBDA → DYNAMODB (UPDATE/EXACT)}
```

## Artefatos de evidência

| Artefato | Caminho |
|---|---|
| J1 (IT — Testcontainers, Station em processo) | [`evidence-lambda-sqs.json`](evidence-lambda-sqs.json) |
| J2 — execução vermelha | [`evidence-lambda-sqs-failure.json`](evidence-lambda-sqs-failure.json) |
| J3 — árvore fundida do consumidor (5 nós) | [`evidence-lambda-sqs-consumer.json`](evidence-lambda-sqs-consumer.json) |
| Emulador Lambda REAL via compose | [`evidence-lambda-sqs-compose.json`](evidence-lambda-sqs-compose.json) |
| UI — visão geral | [`screenshots/07-lambda-station-overview.png`](screenshots/07-lambda-station-overview.png) |
| UI — árvore J1 (LAMBDA → DynamoDB + SQS) | [`screenshots/08-lambda-tree-dynamo-sqs.png`](screenshots/08-lambda-tree-dynamo-sqs.png) |
| UI — inspector do delta EXACT | [`screenshots/09-lambda-inspector-delta.png`](screenshots/09-lambda-inspector-delta.png) |
| UI — árvore J2 vermelha (sucesso parcial) | [`screenshots/10-lambda-tree-failure.png`](screenshots/10-lambda-tree-failure.png) |
| UI — inspector do erro | [`screenshots/11-lambda-inspector-error.png`](screenshots/11-lambda-inspector-error.png) |
| UI — árvore J3 fundida (consumidor) | [`screenshots/12-lambda-tree-consumer.png`](screenshots/12-lambda-tree-consumer.png) |

## Como reproduzir

```bash
# 1) teste automatizado das 3 jornadas (Station em processo + Testcontainers)
mvn -f examples/lambda-sqs/pom.xml -Pit test

# 2) fluxo completo com o emulador Lambda REAL do LocalStack
mvn -f examples/lambda-sqs/pom.xml -DskipTests package
docker compose -f examples/lambda-sqs/docker-compose.yml up    # + invoke de fumaça automático
java -cp target/lambda-sqs-bundle.jar;<m2>/aws-lambda-java-core-1.4.0.jar \
  -Dlocalstack.endpoint=http://localhost:4567 \
  -Dtrace2local.station.endpoint=http://127.0.0.1:19877 \
  tech.neural7.trace2local.examples.lambda.LambdaSqsDemoRun     # J1+J2+J3 no Station do compose

# 3) telas da UI COM validação de consistência UI↔API
node scripts/screenshots/capture-lambda-station.mjs
```

## Ajustes aplicados no loop de melhorias (e o porquê)

**Primeiro loop (ponte Lambda↔Station):**
1. **Nó LAMBDA não existia** — `DefaultSemanticMapper` não conhecia `faas.name`/`faas.invocation_id` → mapeamento adicionado.
2. **Trigger/executionId não chegavam pelo OTLP** — o ingest do Station não parseava `t2l.trigger`/`t2l.execution.id`.
3. **Runtime Lambda não registrava o SDK** no `Trace2LocalOtel` — o interceptor lazy do AWS SDK resolvia o global noop.
4. **Avisos falsos `EVENTS_DROPPED`** em execuções OTLP puras → flag `startDeliberatelyAbsent` no `SpanEndEvent`.
5. **Payload do `lambda invoke` no LocalStack 4.2** exige base64; corrida extração×primeira invocação → `LAMBDA_RUNTIME_ENVIRONMENT_TIMEOUT=180` + retry.
6. Fat jar com `aws-lambda-java-core` `provided`, `META-INF/services` unificado.

**Segundo loop (evidência, consistência e melhorias técnicas):**
7. **Duração de execução NEGATIVA (-255 ms)** — medida pela ordem de processamento dos eventos (a mutação chega depois dos spans, mas é capturada DURANTE eles) → duração agora é a **janela dos spans** (min início → max fim) com piso em zero; teste de regressão em `TraceAssemblerTest`.
8. **Identidade de execução instável** — span tardio com outro `t2l.execution.id` renomeava a execução → o primeiro id explícito (span raiz) vence.
9. **Erro mudo na árvore** — status ERROR sem descrição não virava `ErrorInfo` → o runtime grava `String.valueOf(t)` no status.
10. **J2 (erro)** e **J3 (consumidor)** adicionadas como jornadas testadas: `remoteParentOf` na lib + `OrderBillingProcessor` no demo, span PRODUCER manual com `AWSTraceHeader` no atributo de sistema (descoberta: a instrumentação automática do AWS SDK **não** injeta o header no SendMessage direto do SQS).
11. **Consistência UI↔API validada por script**: o capturador compara os labels do canvas com a API REST do Station e falha se divergirem.
