# Trace2Local — cenário lambda-sqs (Lambda + DynamoDB + SQS no LocalStack)

Projeto de teste/validação da lib **Trace2Local** em uma arquitetura serverless:
uma **AWS Lambda (java21)** que grava um pedido no **DynamoDB** e publica um
evento na **SQS**, tudo rodando no **LocalStack** e observado no **Trace2Local
Station** (modo Companion, ADR-002).

```
invoke ──▶ Lambda order-processor ──▶ DynamoDB PutItem (delta EXACT)
                │                        ▲
                └────────▶ SQS send ─────┘ flush síncrono: OTLP + /t2lingest/v1/mutations
                                           ▼
                                   Trace2Local Station (:19877)
```

## O que é validado

| Item | Como |
|---|---|
| Span raiz LAMBDA | `Trace2LocalLambdaHandler` abre span SERVER com `faas.name`/`faas.invocation_id` → nó `LAMBDA` na árvore |
| Trigger `LAMBDA_EVENT` | `t2l.trigger=lambda_event` no span raiz, parseado no ingest OTLP do Station |
| Delta EXACT do DynamoDB | `Trace2LocalAws.instrument` + `DynamoDbDeltaInterceptor`; mutação via `MutationHttpPublisher` → `/t2lingest/v1/mutations` |
| Nó SQS (produtor) | span PRODUCER manual com `AWSTraceHeader` (a instrumentação automática do AWS SDK **não** injeta o header no SendMessage direto — descoberto no loop de validação) |
| **J2 — jornada de erro** | input `fail=true` lança após o PutItem → execução **FAILED** com a raiz vermelha e o ramo DynamoDB OK (sucesso parcial visível) |
| **J3 — consumidor continua a MESMA árvore** | `OrderBillingProcessor` devolve o parent remoto (`remoteParentOf`) a partir do `AWSTraceHeader` → `LAMBDA → SQS → LAMBDA → DYNAMODB (UPDATE)` em UMA árvore (§4.11) |
| **Monitoramento de IDEMPOTÊNCIA** | `IdempotentProcessor`: guarda condicional (`attribute_not_exists`) + nó BUSINESS — duplicado recusado aparece como DynamoDB **ERROR sem delta**, com o banco provando o não-efeito (3 chamadas → 2 itens). IT `IdempotencyJourneyIT` + demo `IdempotencyDemoRun` + telas 13/14 |
| Flush síncrono | ADR-002/§4.2: o runtime força o flush no fim da invocação (teto 200 ms, configurável) |
| Lambda REAL no LocalStack | fat jar + runtime `java21` + `aws lambda create-function`/`invoke` |

## Rodar

> ⚠️ O IT e o compose usam a **porta 19877** do host (Station). Não rode os dois
> ao mesmo tempo: pare o compose (`docker compose ... down`) antes do `-Pit`.

### 1. Teste automatizado (JUnit + Testcontainers + Station em processo)

```sh
mvn -f examples/lambda-sqs/pom.xml -Pit test
```

Sobe LocalStack real (DynamoDB + SQS), um Station em processo na porta 19877,
invoca o handler como o runtime Lambda faria e verifica as TRÊS jornadas:
**J1** feliz (item real no DynamoDB, mensagem real na fila, árvore com delta
EXACT), **J2** erro (execução FAILED com sucesso parcial) e **J3** consumidor
(árvore fundida `LAMBDA → SQS → LAMBDA → DYNAMODB` via `AWSTraceHeader`).
Evidências: `docs/qa/evidence-lambda-sqs*.json`.

### 2. Fluxo completo com o emulador Lambda do LocalStack (docker compose)

```sh
mvn -f examples/lambda-sqs/pom.xml -DskipTests package   # constrói target/lambda-sqs-bundle.jar
docker compose -f examples/lambda-sqs/docker-compose.yml up
```

O compose sobe LocalStack (dynamodb+sqs+lambda), o Station (em container,
`:19877`), provisiona a tabela/fila, cria a função `order-processor` (runtime
`java21`), e faz uma **invocação de fumaça real** — a árvore aparece em
http://localhost:19877/trace2local.

Captura das telas (evidência visual) **com validação de consistência UI↔API**
(o script compara os labels desenhados no canvas com a API REST do Station e
falha se divergirem):

```sh
java -cp target/lambda-sqs-bundle.jar;<m2>/aws-lambda-java-core-1.4.0.jar \
  -Dlocalstack.endpoint=http://localhost:4567 \
  -Dtrace2local.station.endpoint=http://127.0.0.1:19877 \
  -Dtrace2local.station.token=devtoken \
  tech.neural7.trace2local.examples.lambda.LambdaSqsDemoRun   # J1+J2+J3 no Station do compose
node scripts/screenshots/capture-lambda-station.mjs          # → docs/qa/screenshots/07..12-*.png
```

O Station do compose roda com **token de ingest** (`TRACE2LOCAL_STATION_TOKEN=devtoken`
no Station e na função): OTLP e mutações exigem `Authorization: Bearer devtoken`
— a Lambda e o DemoRun enviam automaticamente (ADR-007/§8.1).

## Detalhes de implementação

- **Endpoint do LocalStack** (dentro da função): cascata `localstack.endpoint`
  (propriedade) → `LOCALSTACK_ENDPOINT` → `AWS_ENDPOINT_URL` (injetada pelo
  LocalStack no ambiente do emulador) → `http://localhost:4566`.
- **Station endpoint** (dentro da função): `TRACE2LOCAL_STATION_ENDPOINT`
  (ADR-002 — obrigatório no modo Lambda); no compose aponta para
  `http://host.docker.internal:19877` (Station publicado no host).
- **Fat jar**: `maven-shade-plugin` unifica `META-INF/services` (ServiceLoader
  do AWS SDK v2 e do OTel) e remove assinaturas; `aws-lambda-java-core` é
  `provided` — a imagem `java:21` do runtime já o fornece (embalar cópia
  própria quebraria o `instanceof` do runtime).
- **SQS send manual** (não `Trace2LocalAws.instrument`): o interceptor OTel do
  AWS SDK não injeta `AWSTraceHeader` no SendMessage direto — o span PRODUCER
  é criado manualmente (atributos `messaging.*` + `aws.sqs.queue.url`) e o
  header vai no atributo de sistema da mensagem, permitindo ao consumidor
  continuar a mesma árvore (§4.11).
