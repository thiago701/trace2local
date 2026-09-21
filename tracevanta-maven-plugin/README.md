# tracevanta-maven-plugin — instalador do TraceVanta

Plugin Maven que **configura automaticamente** o projeto ao adicionar a lib e
faz **engenharia reversa** dos recursos mapeados no canvas, com auditoria de
logs compatível com **Datadog e OpenTelemetry**.

## Goals

| Goal | O que faz | Modifica o projeto? |
| :--- | :--- | :--- |
| `tracevanta:analyze` | escaneia o bytecode (ASM): endpoints Spring, `@TraceVanta`, serviços AWS SDK v2, JDBC; audita SLF4J/`System.out`/`printStackTrace`; gera `target/tracevanta/report.md` + `canvas-map.md` | não (somente leitura) |
| `tracevanta:configure` | adiciona BOM + starter ao `pom.xml` (com backup); cria `tracevanta-business.md`, `application-tracevanta.yml` e `logback-spring.xml` com o padrão de correlação `trace_id`/`span_id` (OTel) + `dd.trace_id`/`dd.span_id` (Datadog) | sim, idempotente (nunca sobrescreve) |

## Uso

```bash
mvn tech.neural7.tracevanta:tracevanta-maven-plugin:0.1.0-SNAPSHOT:analyze
mvn tech.neural7.tracevanta:tracevanta-maven-plugin:0.1.0-SNAPSHOT:configure
```

## Como funciona

- **Engenharia reversa** (`ClassScanner`, ASM): visita classes compiladas e
  detecta anotações por descritor (`RequestMapping`/`GetMapping`/…,
  `@TraceVanta`) e chamadas por owner (`software/amazon/awssdk/services/*`,
  `java/sql`, `org/slf4j/*`, `System.out`, `printStackTrace`) — sem depender
  de source, sem anotação em runtime.
- **Sugestões de logs**: o relatório aponta `System.out`/`printStackTrace`
  (que não correlacionam) e gera o padrão logback com as chaves dos DOIS
  padrões — o starter (`TraceVantaLogs`) injeta os valores no MDC por
  requisição.
- **Configuração**: usa o modelo Maven (`MavenXpp3Reader/Writer`) para editar
  o `pom.xml` sem quebrar o XML existente; arquivos de apoio só são criados
  quando ausentes.

## Limitações declaradas

- O scanner cobre anotações Spring MVC e `@TraceVanta`; outros frameworks
  (JAX-RS etc.) entram no relatório como sugestão manual.
- A edição do `pom.xml` regenera o arquivo (formatação original pode mudar);
  um backup `pom.xml.tracevanta.bak` é criado na primeira alteração.
