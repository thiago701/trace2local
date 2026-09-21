# order-service — app de exemplo do Trace2Local

App de referência das jornadas **JC-1**, **JC-2** e **JC-3** da [SPEC](../../docs/SPEC.md): `POST /orders` grava no DynamoDB e publica no SNS (LocalStack), com o **Trace2Local Embedded** em `http://localhost:9876/trace2local` — uma dependência, zero configuração.

## Rodando (máquina limpa)

```bash
docker compose up --build
```

| URL | O que é |
| :--- | :--- |
| `http://localhost:9876/trace2local` | UI do Trace2Local (catálogo + canvas + inspector) |
| `http://localhost:8080/orders` | A aplicação em si |

### As três jornadas

1. **JC-1 — ver a requisição viajar.** Na UI, abra `POST /orders`, clique em **EXECUTE REQUEST**. A árvore cresce: HTTP → `CreateOrder` (BUSINESS) → `DynamoDB: orders` (com **delta before/after**) → `SNS: order-events`. Clique no nó DynamoDB e veja o `Before: null (New Item)` / `After: {...}`.
2. **JC-2 — depurar a falha.** Confirme o mesmo pedido duas vezes: `POST /orders/{id}/confirm`. A segunda execução fica **vermelha** com `ConditionalCheckFailedException` — abra o nó e veja a exceção com stack recortado.
3. **JC-3 — o assíncrono na MESMA árvore.** O fanout SNS→SQS entrega a mensagem com `AWSTraceHeader`; o `BillingConsumer` (nesta app) continua o trace e o ramo **SQS `billing-queue` → `BillOrder` → DynamoDB `markBilled`** aparece como FILHO do produtor — na mesma execução, sem trocar de janela. Sem consumidor rodando, o nó SNS fica **ORPHANED aguardando consumo**.

## Modo Companion (Station)

```bash
docker compose --profile companion up --build
```

O container `trace2local-station` aceita **OTLP/HTTP** em `/v1/traces` e o canal de mutação em `/t2lingest/v1/mutations` — dois microsserviços e uma Lambda apontando para ele produzem **uma árvore** (ADR-002). Serviços que enviam só OTLP aparecem **sem delta** (degradação prevista da §5.3). O `StationJourneyIT` valida esse caminho ponta a ponta.

## Native Image (M5)

```bash
# na raiz do repositório
mvn -pl examples/order-service -am install -DskipTests
mvn -f examples/order-service/pom.xml -Pnative -DskipTests package
./examples/order-service/target/order-service   # binário nativo com a UI em :9876
```

O critério da SPEC §9.2 não é "compilou" — é "a jornada funciona no binário"; o pipeline de CI roda isso a cada PR.

## Testes

```bash
mvn -pl examples/order-service -am test          # integração Spring sem Docker
mvn -f examples/order-service/pom.xml -Pit test  # E2E real contra LocalStack (Testcontainers, requer Docker)
```

O teste E2E (`OrderJourneyIT`) sobe o LocalStack (DynamoDB + SNS + SQS), dispara `POST /orders` **pelo botão da UI** (`/trace2local/api/execute`), mede o NFR-4 (disparo → `execution.started` no SSE), verifica o delta EXACT, a redaction e o `ConditionalCheckFailedException` da JC-2 — e grava as evidências em [`../../docs/qa/`](../../docs/qa/EVIDENCIA-E2E.md).

### Telas da UI (evidência visual)

```bash
# com a app rodando (compose ou java -jar) e o LocalStack em :4566:
cd ../../scripts/screenshots && npm install && node capture.mjs
```

Gera 6 capturas reais em `docs/qa/screenshots/` (catálogo, árvore JC-1 com o consumidor SQS, inspector com delta before/after, inspector do SQS, execução vermelha da JC-2 e o erro `ConditionalCheckFailedException`).
