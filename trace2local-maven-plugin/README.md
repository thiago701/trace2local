# trace2local-maven-plugin — instalador do Trace2Local

Plugin Maven que **configura automaticamente** o projeto ao adicionar a lib e
faz **engenharia reversa** dos recursos mapeados no canvas, com auditoria de
logs compatível com **Datadog e OpenTelemetry**.

## Goals

| Goal | O que faz | Modifica o projeto? |
| :--- | :--- | :--- |
| `trace2local:analyze` | escaneia o bytecode (ASM): endpoints Spring, `@Trace2Local`, serviços AWS SDK v2, JDBC; audita SLF4J/`System.out`/`printStackTrace`; gera `target/trace2local/report.md` + `canvas-map.md` | não (somente leitura) |
| `trace2local:configure` | adiciona BOM + starter ao `pom.xml` (com backup); cria `trace2local-business.md`, `application-trace2local.yml` e `logback-spring.xml` com o padrão de correlação `trace_id`/`span_id` (OTel) + `dd.trace_id`/`dd.span_id` (Datadog) | sim, idempotente (nunca sobrescreve) |

## Uso

```bash
mvn tech.neural7.trace2local:trace2local-maven-plugin:0.1.0-SNAPSHOT:analyze
mvn tech.neural7.trace2local:trace2local-maven-plugin:0.1.0-SNAPSHOT:configure
```

## Como funciona

- **Engenharia reversa** (`ClassScanner`, ASM): visita classes compiladas e
  detecta anotações por descritor (`RequestMapping`/`GetMapping`/…,
  `@Trace2Local`) e chamadas por owner (`software/amazon/awssdk/services/*`,
  `java/sql`, `org/slf4j/*`, `System.out`, `printStackTrace`) — sem depender
  de source, sem anotação em runtime.
- **Sugestões de logs**: o relatório aponta `System.out`/`printStackTrace`
  (que não correlacionam) e gera o padrão logback com as chaves dos DOIS
  padrões — o starter (`Trace2LocalLogs`) injeta os valores no MDC por
  requisição.
- **Configuração**: usa o modelo Maven (`MavenXpp3Reader/Writer`) para editar
  o `pom.xml` sem quebrar o XML existente; arquivos de apoio só são criados
  quando ausentes.

## Limitações declaradas

- O scanner cobre anotações Spring MVC e `@Trace2Local`; outros frameworks
  (JAX-RS etc.) entram no relatório como sugestão manual.
- A edição do `pom.xml` regenera o arquivo (formatação original pode mudar);
  um backup `pom.xml.trace2local.bak` é criado na primeira alteração.
