# ADR-001 — OpenTelemetry SDK como base de coleta

- **Status:** Aceita (2026-09-18)
- **Decisores:** Thiago Gonçalo (product owner), Squad AI
- **Relacionada:** ADR-008 (camada semântica), ADR-003 (o que o OTel não resolve)

## Contexto

O Trace2Local precisa capturar HTTP de entrada, chamadas ao AWS SDK v2 (DynamoDB, SNS, SQS), JDBC e métodos de negócio — e precisa fazer isso sob uma restrição inegociável: **compatibilidade com GraalVM Native Image**, o que elimina `-javaagent` e qualquer transformação de bytecode em runtime.

Fatos apurados (fontes em `../PESQUISA-2026-09-18.md`):

- O javaagent do OpenTelemetry **não funciona** em Native Image — documentado pelo próprio OTel; a issue do GraalVM (GR-55707) trata transformação em runtime como *non-goal*, e a documentação do GraalVM afirma que JVMTI e ferramentas baseadas em bytecode não são suportadas.
- Existe caminho oficial **sem agente**: `opentelemetry-spring-boot-starter` (AOP + DI + `BeanPostProcessor`), `opentelemetry-aws-sdk-2.2` como `ExecutionInterceptor` registrado manualmente, `opentelemetry-jdbc` como wrapper de `DataSource`.
- Versões correntes: SDK 1.66.0, instrumentation 2.31.1.

## Decisão

Usar o **OpenTelemetry Java SDK como substrato de coleta**, consumindo a *library instrumentation* sem agente, e construir a identidade do Trace2Local em **duas camadas próprias** por cima:

1. **Ponte** (`trace2local-otel`): `SpanProcessor` + `SpanExporter` in-process que traduzem `SpanData` para o TVEM.
2. **Camada semântica** (ADR-008): o que transforma `DynamoDb.PutItem` em "DynamoDB: orders, item ORDER#88291 criado".

O Trace2Local **acrescenta** um processor ao pipeline do desenvolvedor; **não substitui** o exporter dele. Quem já manda OTLP para o Jaeger continua mandando.

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **Instrumentação 100% própria** (interceptors Spring + AWS + JDBC, sem OTel) | Reescreve o que já existe, maduro e mantido; perde propagação W3C de graça, perde interoperabilidade com Jaeger/Tempo, e dobra o custo de manutenção — que é o risco R-09, o mais letal para um projeto de um mantenedor |
| **Javaagent próprio** | Mata o princípio AOT-first, que é a razão de existir do produto (§9.1 da SPEC) |
| **Micrometer Observation como base** | Excelente dentro do Spring, mas amarra o núcleo a um framework — viola a regra de `trace2local-core` sem dependência de framework |
| **Híbrido desde o início** (OTel onde existe, interceptor próprio onde não existe) | É o destino provável, mas começar assim mantém dois caminhos de código antes de saber se o segundo é necessário. P1-Simplicidade: o híbrido nasce por evidência, via SPI (§4.7), não por antecipação |

## Consequências

**Boas.** Instrumentação madura de graça; compatibilidade nativa comprovada por exemplo oficial; propagação W3C pronta; o dev pode reaproveitar a telemetria em qualquer backend OTLP; o Trace2Local fica pequeno o suficiente para uma pessoa manter.

**Ruins, e assumidas.**

1. **Cobertura limitada ao que tem *library instrumentation*.** Em Native Image, dependências sem instrumentação publicada simplesmente não aparecem. Mitigação: a SPI (§4.7) e a UI declarando "não instrumentado" — nunca fingindo completude. (Risco R-02)
2. **Dependência de um ecossistema que se move.** SDK e instrumentation versionam separadamente e a instrumentation costuma mirar uma versão atrás do SDK. Mitigação: BOM fixo e teste de contrato.
3. **`opentelemetry-jdbc` ainda é `-alpha`.** Sem garantia de SemVer numa peça central. Mitigação: isolar atrás da SPI; plano B é um wrapper próprio de `DataSource`, que é código pequeno. (Risco R-04)
4. **O modelo do OTel não comporta o diferencial do produto** — delta de dados não cabe num span. Consequência tratada no ADR-003.
