package tech.neural7.tracevanta.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Gera os relatórios markdown do instalador (análise + mapa do canvas + sugestões de logs). */
public final class ReportWriter {

    private ReportWriter() {}

    public static void write(Findings f, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("canvas-map.md"), canvasMap(f), StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("report.md"), report(f), StandardCharsets.UTF_8);
    }

    static String canvasMap(Findings f) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Mapa do canvas — recursos identificados por engenharia reversa\n\n");
        sb.append("Estes recursos aparecerão na UI do TraceVanta (`:9876/tracevanta`).\n\n");
        sb.append("## Endpoints (catálogo + disparo pela UI)\n\n");
        if (f.endpoints.isEmpty()) {
            sb.append("_Nenhum endpoint Spring encontrado._\n");
        } else {
            sb.append("| Método | Rota | Handler |\n| --- | --- | --- |\n");
            for (Findings.Endpoint e : f.endpoints) {
                sb.append("| ").append(e.method()).append(" | `").append(e.path())
                        .append("` | `").append(e.handler()).append("` |\n");
            }
        }
        sb.append("\n## Métodos de negócio (@TraceVanta → nós BUSINESS)\n\n");
        if (f.businessMethods.isEmpty()) {
            sb.append("_Nenhum método anotado com @TraceVanta — adicione nos serviços para nós de negócio na árvore._\n");
        } else {
            for (String b : f.businessMethods) {
                sb.append("- `").append(b).append("`\n");
            }
        }
        sb.append("\n## Integrações (nós na árvore)\n\n");
        sb.append("- AWS SDK v2: ").append(f.awsServices.isEmpty() ? "—" : String.join(", ", f.awsServices)).append("\n");
        sb.append("- JDBC/SQL: ").append(f.usesJdbc ? "presente (nó SQL)" : "—").append("\n");
        return sb.toString();
    }

    static String report(Findings f) {
        StringBuilder sb = new StringBuilder();
        sb.append("# TraceVanta — análise de engenharia reversa e observabilidade\n\n");
        sb.append("Classes escaneadas: **").append(f.scannedClasses).append("**\n\n");
        sb.append("## Resumo\n\n");
        sb.append("| Recurso | Quantidade |\n| --- | --- |\n");
        sb.append("| Endpoints Spring | ").append(f.endpoints.size()).append(" |\n");
        sb.append("| Métodos @TraceVanta | ").append(f.businessMethods.size()).append(" |\n");
        sb.append("| Serviços AWS SDK v2 | ").append(f.awsServices.size()).append(" |\n");
        sb.append("| Usos de SLF4J | ").append(f.slf4jUsages).append(" |\n");
        sb.append("| `System.out/err` | ").append(f.systemOutSites.size()).append(" |\n");
        sb.append("| `printStackTrace` | ").append(f.printStackTraceSites).append(" |\n\n");

        sb.append("## Sugestões de LOGS (compatíveis com Datadog e OpenTelemetry)\n\n");
        List<String> suggestions = f.systemOutSites.stream()
                .map(s -> "- **Substitua `System.out` por SLF4J** em `" + s
                        + "` — logs fora do SLF4J não carregam `trace_id`/`span_id`/`dd.trace_id` e não correlacionam com o trace.")
                .collect(Collectors.toList());
        if (f.slf4jUsages == 0) {
            suggestions.add("- **Adicione SLF4J + Logback** e um `logger` por classe — a correlação de trace do TraceVanta só funciona via MDC do SLF4J.");
        }
        if (f.printStackTraceSites > 0) {
            suggestions.add("- **Troque `printStackTrace` por `logger.error(\"...\", excecao)`** (" + f.printStackTraceSites
                    + " ocorrência(s)) — o stack entra no padrão de log e no coletor, não só no stderr.");
        }
        if (!f.hasLogbackConfig && !f.hasLog4j2Config) {
            suggestions.add("- **Sem configuração de logging encontrada** — rode `mvn tracevanta:configure` para criar `logback-spring.xml` com o padrão de correlação.");
        } else if (!f.logPatternHasTraceIds) {
            suggestions.add("- **O padrão de log atual não imprime os IDs de trace** — inclua no pattern:\n"
                    + "  `trace_id=%X{trace_id:-} span_id=%X{span_id:-} dd.trace_id=%X{dd.trace_id:-} dd.span_id=%X{dd.span_id:-}`\n"
                    + "  (o instalador faz isso automaticamente).");
        } else {
            suggestions.add("- Padrão de log já imprime os IDs de trace ✅");
        }
        if (!f.hasBusinessGlossary) {
            suggestions.add("- **Crie `src/main/resources/tracevanta-business.md`** para a narrativa de negócio da aba STORY (ou rode `mvn tracevanta:configure`).");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("- Logs em ordem ✅");
        }
        for (String s : suggestions) {
            sb.append(s).append("\n");
        }

        sb.append("\n## Portabilidade de logs (padrões)\n\n");
        sb.append("O TraceVanta injeta no MDC as chaves dos DOIS ecossistemas:\n\n");
        sb.append("| Chave | Formato | Quem lê |\n| --- | --- | --- |\n");
        sb.append("| `trace_id` / `span_id` | hex 128/64 bits | OpenTelemetry (coletor Filelog, OTLP logs) |\n");
        sb.append("| `dd.trace_id` / `dd.span_id` | decimal unsigned 64 bits | Datadog (correlação de logs padrão) |\n\n");
        sb.append("Ou seja: os MESMOS logs correlacionam no trace local e em pipelines Datadog/OTel.\n");
        return sb.toString();
    }
}
