<div align="center">

<img src="docs/qa/screenshots/07-lambda-station-overview.png" alt="Trace2Local" width="96" style="border-radius:12px">

# Trace2Local

**See your request travel through the system.**

*Uma dependência. Zero configuração. A árvore completa do que a sua aplicação fez — ao vivo, no navegador, sem deixar a sua máquina.*

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](#compatibilidade-v01)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-C71A36.svg)](https://maven.apache.org)
[![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-lightgrey.svg)](CHANGELOG.md)
[![CI](https://github.com/thiago701/trace2local/actions/workflows/ci.yml/badge.svg)](https://github.com/thiago701/trace2local/actions/workflows/ci.yml)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

</div>

---

O **Trace2Local** transforma a sua aplicação Java numa *runtime canvas* interativa:
descubra endpoints, dispare requisições do próprio navegador e acompanhe a árvore
da execução em tempo real — código, **DynamoDB (delta before/after)**, SNS, SQS e
SQL — em `http://localhost:9876/trace2local`.

Ergonomia do Swagger UI, ambição do tracing distribuído: **nada sai da sua máquina**,
tudo em memória, e a UI declara o que não conseguiu observar — nunca inventa.

---

## 📚 Índice

- [Por que Trace2Local](#-por-que-trace2local)
- [Demonstração](#-demonstração)
- [Quickstart](#-quickstart)
- [Instalador Maven](#-instalador-maven)
- [Instrumentação](#-instrumentação)
- [Como funciona](#-como-funciona)
- [Arquitetura e módulos](#-arquitetura-e-módulos)
- [Configuração](#-configuração-trace2local)
- [Segurança](#-segurança)
- [Compatibilidade](#-compatibilidade-v01)
- [Testes e qualidade](#-testes-e-qualidade)
- [Documentação](#-documentação)
- [Roadmap](#-roadmap)
- [Declarações de honestidade](#️-leia-antes-de-usar)
- [Contribuindo](#-contribuindo)
- [Licença](#-licença)

---

## ✨ Por que Trace2Local

| | |
|---|---|
| 🔍 **Árvore em tempo real** | Cada requisição vira uma árvore de nós (HTTP, negócio, banco, mensageria) com waterfall de tempos, self-time e erros — a UI reconstrói a árvore como o assembler faz |
| 📊 **Delta de dados EXACT** | `before/after` real do DynamoDB (ADR-003) e delta `inferred` de SQL — mutações correlacionadas por `spanId`, fora do pipeline OTel |
| 🔌 **Sem agente, sem config** | Uma dependência Maven. Nada de `-javaagent` — compatível com **GraalVM Native Image** (a razão de existir do produto) |
| 🧩 **Domínio-agnóstico** | O núcleo não conhece "pedido" nem "cliente": e-commerce, logística, fintech, saúde — qualquer área de negócio funciona sem customização |
| 🛡️ **Local-first** | Bind `127.0.0.1` por padrão, redaction **na origem**, token Bearer opcional no ingest, CSP restritivo — [SECURITY.md](SECURITY.md) |
| 📡 **Dois modos** | **Embedded** (a UI sobe no seu JVM) ou **Companion/Station** (Lambda, `sam local` e **multi-serviço em UMA árvore** via OTLP) |
| 🧪 **Testável** | Asserções sobre a árvore nos seus testes (`trace2local-testing`), E2E com LocalStack real no CI |
| 📦 **Honestidade por design** | Spans perdidos, delta indisponível, cobertura sem agente — tudo **declarado na UI**, nunca presumido (invariantes I1–I3) |

---

## 🎬 Demonstração

| Visão geral + execuções | Árvore com waterfall | Delta EXACT | Execução com erro |
|---|---|---|---|
| ![overview](docs/qa/screenshots/07-lambda-station-overview.png) | ![tree](docs/qa/screenshots/08-lambda-tree-dynamo-sqs.png) | ![delta](docs/qa/screenshots/09-lambda-inspector-delta.png) | ![error](docs/qa/screenshots/10-lambda-tree-failure.png) |

---

## 🚀 Quickstart

### Spring Boot (Embedded — padrão)

```xml
<!-- pom.xml -->
<dependency>
  <groupId>tech.neural7.trace2local</groupId>
  <artifactId>trace2local-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@RestController
public class PedidoController {
    @PostMapping("/pedidos")
    public Pedido criar(@RequestBody NovoPedido request) {
        return service.criar(request); // @Trace2Local no serviço = nó BUSINESS na árvore
    }
}
```

Suba a app e abra **`http://localhost:9876/trace2local`** — escolha `POST /pedidos`,
clique **EXECUTE REQUEST** e veja a requisição viajar.

### AWS Lambda (modo Companion com Station)

```bash
docker run -d --name trace2local-station -p 9876:9876 \
  -e TRACE2LOCAL_BIND_ADDRESS=0.0.0.0 -e TRACE2LOCAL_ALLOW_NON_LOOPBACK=true \
  -e TRACE2LOCAL_STATION_TOKEN=seu-token trace2local-station:0.1.0
```

```java
public class MinhaFuncao extends Trace2LocalLambdaHandler<Map<String, String>, String> {
    @Override
    protected String handle(Map<String, String> input, Context ctx) { /* ... */ }
}
// envs da função: TRACE2LOCAL_STATION_ENDPOINT + TRACE2LOCAL_STATION_TOKEN
```

O runtime abre o span raiz (nó `LAMBDA`), correlaciona mutações e faz **flush
síncrono** no fim da invocação (ADR-002) — a árvore aparece no Station.

### Exemplos completos

| Exemplo | O que demonstra | Como rodar |
|---|---|---|
| [`examples/order-service`](examples/order-service/) | Spring Boot + DynamoDB + SNS/SQS fanout + consumidor (JC-1/2/3) + Native Image | `docker compose up --build` |
| [`examples/lambda-sqs`](examples/lambda-sqs/) | Lambda java21 + DynamoDB + SQS no LocalStack, consumidor na MESMA árvore, token Bearer | `./mvnw -f examples/lambda-sqs/pom.xml -DskipTests package && docker compose -f examples/lambda-sqs/docker-compose.yml up` |

---

## 🧰 Instalador Maven

O plugin `trace2local-maven-plugin` faz a **configuração automática** do projeto
e a **engenharia reversa** dos recursos que aparecem no canvas — além de
auditar os logs e sugerir/gerar o padrão compatível com **Datadog e
OpenTelemetry**.

### 1. Analisar (somente leitura — engenharia reversa)

```bash
./mvnw tech.neural7.trace2local:trace2local-maven-plugin:0.1.0-SNAPSHOT:analyze
```

Escaneia o bytecode compilado e gera `target/trace2local/`:

- **`canvas-map.md`** — o que será mapeado na UI: endpoints Spring (catálogo +
  disparo), métodos `@Trace2Local` (nós BUSINESS), serviços AWS SDK v2 e JDBC;
- **`report.md`** — higiene de logs: usos de SLF4J, `System.out`,
  `printStackTrace`, e sugestões concretas (ex.: *"Substitua System.out por
  SLF4J em X — logs fora do SLF4J não correlacionam com o trace"*).

### 2. Configurar (aplica a instalação)

```bash
./mvnw tech.neural7.trace2local:trace2local-maven-plugin:0.1.0-SNAPSHOT:configure
```

Idempotente (nunca sobrescreve arquivo existente):

| Ação | Resultado |
| :--- | :--- |
| `pom.xml` | adiciona `trace2local-bom` (import) + `trace2local-spring-boot-starter` (backup em `pom.xml.trace2local.bak`) |
| `src/main/resources/trace2local-business.md` | glossário de negócio para a aba STORY (se ausente) |
| `src/main/resources/application-trace2local.yml` | config inicial (porta, redaction, retention, delta DynamoDB) |
| `src/main/resources/logback-spring.xml` | padrão de log com correlação de trace nos DOIS padrões |

### 3. Logs portáteis (Datadog + OpenTelemetry)

O starter injeta no MDC, por requisição, as chaves dos dois ecossistemas:

| Chave | Formato | Quem lê |
| :--- | :--- | :--- |
| `trace_id` / `span_id` | hex 128/64 bits | OpenTelemetry (coletor Filelog, OTLP logs) |
| `dd.trace_id` / `dd.span_id` | decimal unsigned 64 bits | Datadog (correlação de logs padrão) |

Resultado real (demo payment-service):

```
INFO PaymentController - trace_id=259c9030880bbf221741f9751ba5189c span_id=87b3151e2ab98bac
                        dd.trace_id=1675894817728829596 dd.span_id=9778182435261483948
                        - Pix PIX-LOG2 criado
```

Os MESMOS logs correlacionam no trace local e em pipelines Datadog/OTel —
adicione `logger.info(...)` de negócio nos seus serviços (o `analyze` aponta
onde faltam).

---

## 🔌 Instrumentação

```java
// AWS SDK v2 — delta EXACT do DynamoDB + semântica SNS/SQS
DynamoDbClient ddb = Trace2LocalAws.instrument(DynamoDbClient.builder(), traceVantaConfig).build();

// SQL — semântica + delta inferred (opcional)
DataSource ds = Trace2LocalJdbc.wrap(myDataSource, traceVantaConfig);

// Negócio — um nó BUSINESS por método
@Trace2Local("CriarPedido")
public Pedido criar(NovoPedido r) { /* ... */ }

// Qualquer biblioteca sem library instrumentation — SPI via ServiceLoader (AOT-safe)
public interface Trace2LocalExtension {
    default void contribute(NodeBuilder node, SpanView span) {}
    default Optional<DataMutation> captureMutation(MutationContext ctx) { return Optional.empty(); }
    default RedactionPolicy redactionPolicy() { return RedactionPolicy.INHERIT; }
    default List<EndpointDescriptor> discoverEndpoints() { return List.of(); }
    default int order() { return 0; }
}
```

---

## ⚙️ Como funciona

```mermaid
flowchart LR
    I[Instrumentação<br/>starter · aws · jdbc · lambda · @Trace2Local] -->|SpanStart/End + MutationEvent| B[Ring Buffer 4096<br/>descarte declarado na borda]
    B --> A[Assembler · virtual thread<br/>TVEM · invariantes I1-I3]
    A --> S[ExecutionStore<br/>acervo LRU]
    S --> H[HTTP · REST + SSE 20fps] --> U[UI · canvas + inspector]
```

1. **Ponte** (`trace2local-otel`): um `SpanProcessor` *acrescentado* ao pipeline
   OTel do dev — nunca o substitui. Se você já exporta para o Jaeger, continua exportando.
2. **Ring buffer** (ADR-006): fila limitada com `offer()`, nunca `put()` — sob
   rajada, eventos são descartados **e o descarte é exibido**.
3. **Assembler**: monta o TVEM com eventos fora de ordem; órfão é reparentado
   com aviso (I1); `selfTime` nunca negativo (I2); fidelidade do delta declarada (I3).
4. **Delta de dados** (ADR-003): canal lateral correlacionado por `spanId` —
   payload sensível nunca entra no pipeline OTel do dev.
5. **UI** (ADR-004/005): WebJar offline no próprio JAR, um `EventSource` por aba,
   tema escuro, teclado completo, waterfall — **nenhum byte sai da sua máquina**.

### Modos de execução (ADR-002)

| | **Embedded** (padrão) | **Companion / Station** |
| :--- | :--- | :--- |
| Onde roda a UI | No JVM da sua app, `:9876` | Container `trace2local-station`, `:9876` |
| Para | Spring Boot local, `docker compose`, testes | Lambda, `sam local`, **multi-serviço em uma árvore** |
| Telemetria | SpanProcessor in-process | OTLP/HTTP `/v1/traces` + `/t2lingest/v1/mutations` (Bearer opcional) |
| Flush em Lambda | — | **Síncrono no fim da invocação** (o ambiente congela), teto 200 ms |

---

## 🧩 Arquitetura e módulos

Visão completa (regras de dependência, fluxo, SPI): **[docs/ARQUITETURA.md](docs/ARQUITETURA.md)**.

| Módulo | Papel |
| :--- | :--- |
| `trace2local-bom` | BOM: versões do projeto **e** de terceiros — declare sem versão |
| `trace2local-core` | TVEM, ring buffer, assembler, redaction, config, SPI — POJO + JDK |
| `trace2local-otel` | Ponte OTel: `SpanProcessor`, `SemanticMapper` (anti-corrupção, ADR-008) |
| `trace2local-ui` | Assets da UI (WebJar, offline absoluto, zero referência externa) |
| `trace2local-server` | REST + SSE sobre `com.sun.net.httpserver` (sem framework) |
| `trace2local-spring-boot-starter` | Autoconfig Boot, catálogo, launcher, guarda de produção, hints AOT |
| `trace2local-aws` | Delta DynamoDB (EXACT) + semântica SNS/SQS |
| `trace2local-jdbc` | Semântica SQL + delta `inferred` |
| `trace2local-lambda` | `Trace2LocalLambdaHandler` com flush síncrono |
| `trace2local-station` | Station standalone do modo Companion (ingest OTLP + mutações) |
| `trace2local-testing` | JUnit 5 + asserções sobre a árvore (para os seus testes) |
| `trace2local-architecture` | Regras ArchUnit que travam a arquitetura no CI |

---

## ⚙️ Configuração (`trace2local.*`)

| Propriedade | Padrão | Nota |
| :--- | :--- | :--- |
| `enabled` | `true` em dev | kill switch: `-Dtrace2local.enabled=false` desliga tudo |
| `port` | `9876` | `0` = efêmera |
| `bind-address` | `127.0.0.1` | alterar exige `allow-non-loopback=true` |
| `buffer.capacity` | `4096` | eventos; descarte na borda é exibido na UI |
| `retention.max-executions` | `100` | LRU em memória |
| `payload.max-bytes` | `8192` | por nó |
| `redaction.mode` | `strict` | `strict` \| `keys` \| `off` |
| `aws.dynamodb.capture-before` | `true` (dev) | eleva `ReturnValues` e restaura a resposta (R-01) |
| `jdbc.mutation-capture` | `off` | `off` \| `inferred` |
| `station.endpoint` | — | modo Companion |
| `station.token` | — | Bearer do ingest (env `TRACE2LOCAL_STATION_TOKEN`) |
| `flush-timeout-ms` | `200` | Lambda |

---

## 🔒 Segurança

Política completa e modelo de ameaças: **[SECURITY.md](SECURITY.md)**.

- Bind **loopback por padrão**; exposição exige flag explícita + avisos.
- **Redaction na origem**: chaves sensíveis + padrões de valor (JWT, chaves AWS,
  cartão com Luhn, CPF/CNPJ, tokens GitHub, hashes bcrypt/argon2…) — mitigação declarada.
- **Token Bearer opcional** no ingest; hardening HTTP (CSP sem `unsafe-inline`,
  `nosniff`, `no-referrer`, `no-store`); limites de entrada; descarte declarado.
- Auditoria de CVEs das versões pinadas: nenhuma afetada (Jackson 2.22.2 e
  AssertJ 3.27.7 são exatamente as versões corrigidas — não rebaixar).
- Reporte: GitHub Security Advisory ou `security@neural7.tech`.

---

## 📦 Compatibilidade (v0.1)

| Eixo | Suportado |
| :--- | :--- |
| **JDK (construir/rodar)** | **21+** (CI em 21 e 25; bytecode baseline 21 — ADR-009) |
| Maven | **3.9+** (enforced; wrapper incluído) |
| Spring Boot | **4.0/4.1** |
| OpenTelemetry | SDK **1.66** / instrumentation **2.31** (semconv 1.44) |
| AWS SDK | **v2** (DynamoDB/SNS/SQS); Lambda `java21`/`java25` |
| GraalVM | **Native Image (JDK 25)** |
| LocalStack | **3/4** (validado em 4.2) |
| Navegador (UI) | Chrome/Edge/Firefox modernos (offline absoluto — ADR-005) |

Em **0.x a API pode quebrar entre minors** (SemVer a partir do 1.0.0 — ADR-010).

---

## 🧪 Testes e qualidade

```bash
./mvnw install                              # unidade + propriedade + contrato + ArchUnit
./mvnw -Pit -pl examples/order-service verify   # E2E LocalStack real (Docker)
./mvnw -Pit -pl examples/lambda-sqs verify      # E2E Lambda+SQS (Docker)
```

| Camada | O que cobre |
| :--- | :--- |
| Propriedade | Invariantes I1–I3 do TVEM sob eventos fora de ordem e perdidos (jqwik) |
| Contrato | `SemanticMapper` por fixture de span — bump do OTel quebra aqui, não na UI |
| Arquitetura | Regras de dependência + nenhum literal OTel fora da ponte + UI sem referência externa |
| Segurança | Corpus de redaction, SSRF do launcher, guarda de produção, token Bearer do ingest |
| E2E | LocalStack real (Testcontainers) + consistência UI↔API nas capturas de tela |

---

## 📖 Documentação

| Documento | Conteúdo |
| :--- | :--- |
| [SPEC.md](docs/SPEC.md) | Especificação técnica e arquitetural completa |
| [ARQUITETURA.md](docs/ARQUITETURA.md) | Módulos, fluxo de runtime, pontos de extensão |
| [docs/adr/](docs/adr/) | Decisões de arquitetura registradas (ADR-001…010) |
| [SECURITY.md](SECURITY.md) | Modelo de ameaças, limites declarados, reporte |
| [CHANGELOG.md](CHANGELOG.md) | Histórico completo desde o primeiro commit |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Como contribuir (convenções e definição de pronto) |
| [docs/qa/](docs/qa/) | Evidências de QA: E2E, UX, consistência, telas |

---

## 🗺️ Roadmap

M0–M6 implementados na v0.1; **M5 (prova AOT)** no CI; **M7** entrega o Station.
Visões DAG/Waterfall/Sequence e exportação OTLP entram na v0.2 — detalhes em
[SPEC §11.1](docs/SPEC.md).

---

## ⚠️ LEIA ANTES DE USAR — declarações de honestidade

1. **Ferramenta de DEV-TIME — não empacote em produção.** O starter se
   autodesabilita fora de dev e **falha o boot** se forçado sem
   `trace2local.i-know-what-im-doing=true` (SPEC §8.4).
2. **Quem tem a máquina, tem a UI.** Bind `127.0.0.1`, sem autenticação — em
   loopback, auth seria teatro (ADR-007). Expor além do loopback exige flag
   explícita e emite WARN; para uso remoto, túnel SSH.
3. **A captura do delta do DynamoDB modifica a sua requisição** (eleva
   `ReturnValues` e restaura a resposta — há teste provando a restauração;
   R-01). Desligue com `trace2local.aws.dynamodb.capture-before=false`.
4. **Redaction é mitigação, não garantia.** Campo de negócio com nome inocente
   passa — o limite está declarado, não escondido (SPEC §8.3).
5. **Cobertura sem agente é limitada** — a UI diz "não instrumentado", nunca
   finge completude (R-02).

**Desvios registrados da SPEC** (honestidade acima de tudo — detalhes nos ADRs):

| Item | SPEC | v0.1 |
| :--- | :--- | :--- |
| `UpdateItem` before/after | §4.10: ambos em uma chamada | **FECHADO** com `Trace2LocalAws.instrumentWithReadBack` (opcional: `before` do `ALL_OLD` + `after` exato por releitura dentro do span); o padrão `instrument` mantém `after` EXACT com `before=null` **declarado na UI** (a API do DynamoDB devolve UM conjunto por chamada) |
| SSE `execution.completed` | §5.2: campos flat (`status`, `duration`, `metrics`) | **FECHADO**: `{"executionId","status","duration" (ISO-8601),"metrics","execution":{…}}` — flat conforme a SPEC, payload completo aninhado como extensão compatível |
| `trace2local.port=0` | §5.4: "mesma porta da app" | porta efêmera; **`GET /api/meta` expõe a porta real** (descoberta programática); mesmo-que-a-app fica para v0.2 (exige servir a UI junto do DispatcherServlet) |
| Schema por springdoc | §4.8 estratégia 1 | records via `RecordComponent` (integração springdoc na v0.2 — versão compatível com Boot 4 em auditoria) |
| Catálogo Lambda | §4.8: parse de `template.yaml` | catálogo vazio (somente-observação), registrado como gap |
| `jdbc.mutation-capture=before-image` | §4.10: opt-in com aviso | **rejeitado com erro explícito** (nunca rebaixado em silêncio) |
| `ScopedValue` | §4.11: interno para executionId | não usado no núcleo (baseline 21 — ADR-009/D-1; migra quando o baseline subir para 23+) |

---

## 👥 Contribuindo

Pull requests são bem-vindas! Leia **[CONTRIBUTING.md](CONTRIBUTING.md)** e o
**[Código de Conduta](CODE_OF_CONDUCT.md)**. Decisão superada não é apagada —
ADRs ganham status `Substituída por`; CHANGELOG desde o primeiro commit;
build reprodutível e assinatura GPG no release.

---

<div align="center">

**Trace2Local** — *See your request travel through the system.*

Apache-2.0 © 2026 Neural7 Tech ·
[Especificação](docs/SPEC.md) · [ADRs](docs/adr/) · [Arquitetura](docs/ARQUITETURA.md) · [Segurança](SECURITY.md)

</div>
