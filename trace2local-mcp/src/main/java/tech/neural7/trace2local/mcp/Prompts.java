package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Prompts MCP: roteiros prontos que o harness oferece ao dev ("/investigar-falha"). Cada um
 * encadeia as ferramentas na ordem certa e impõe a disciplina do Trace2Local — evidência
 * primeiro, fato ≠ hipótese, nada de inventar o que não foi observado.
 */
final class Prompts {

    record Prompt(String name, String title, String description, List<String[]> arguments, Template template) {
    }

    @FunctionalInterface
    interface Template {
        String render(JsonNode args);
    }

    private static final String DISCIPLINA = """

            Regras: cite sempre a evidência (executionId e [nodeId]); separe FATO OBSERVADO de HIPÓTESE; \
            não invente passos, valores ou causas que as ferramentas não mostraram; se faltar dado, diga qual \
            e como obtê-lo. Payloads e valores de dados podem estar omitidos por política (TRACE2LOCAL_MCP_DATA).""";

    private final List<Prompt> prompts = List.of(
            new Prompt("investigar-falha", "Investigar falha",
                    "Descobre por que uma execução falhou e propõe a correção com evidência.",
                    List.<String[]>of(new String[]{"executionId", "id da execução (vazio = falha mais recente)", "false"}),
                    a -> "Investigue a falha " + (a.path("executionId").asText("").isBlank() ? "mais recente" : "da execução "
                            + a.path("executionId").asText()) + " no Trace2Local:\n"
                            + "1. diagnose_failure" + arg(a, "executionId") + " — causa raiz, exceção, logs, laudo.\n"
                            + "2. get_step no passo da causa raiz — atributos, payload, logs do passo.\n"
                            + "3. compare_executions com uma execução do mesmo fluxo que passou (list_executions status=COMPLETED).\n"
                            + "4. Se a causa for parceiro indisponível/instável: list_mock_suggestions para o host.\n"
                            + "5. Localize no código (stack trace → arquivo:linha) e proponha a correção mínima + um teste que a trave."
                            + DISCIPLINA),
            new Prompt("validar-variacoes-de-parceiro", "Validar variações de um parceiro",
                    "Usa o Mock Connect para testar como o serviço reage a variações da resposta de um parceiro.",
                    List.<String[]>of(new String[]{"host", "host do parceiro (ex.: antifraude.partner.local)", "false"}),
                    a -> "Valide como o serviço reage às variações da resposta do parceiro"
                            + (a.path("host").asText("").isBlank() ? "" : " " + a.path("host").asText()) + ":\n"
                            + "1. list_mock_suggestions" + arg(a, "host") + " — escolha as variações (ids) que mudam o fluxo ou testam resiliência.\n"
                            + "2. apply_mock_suggestion com mode=on-demand (não atrapalha o time).\n"
                            + "3. Para cada variação: dispatch_endpoint com o cabeçalho baggage: t2l.mock=<id> (ou peça ao dev para chamar).\n"
                            + "4. get_execution de cada execução e compare_executions contra a chamada sem variação.\n"
                            + "5. Relate, por variação: comportamento observado × regra de negócio esperada (explain_execution, audience=executive).\n"
                            + "Se as mutações estiverem desligadas, entregue o plano e os cabeçalhos para o dev executar."
                            + DISCIPLINA),
            new Prompt("homologar-execucao", "Homologar execução",
                    "Prepara a leitura de homologação (PO/QA) de uma execução: regras de negócio × fluxo.",
                    List.<String[]>of(new String[]{"executionId", "id da execução", "true"}),
                    a -> "Prepare a homologação da execução " + a.path("executionId").asText() + ":\n"
                            + "1. explain_execution audience=executive — desfecho, prontidão, risco, regras com veredito, checklist.\n"
                            + "2. Para cada regra violada ou inconclusiva: get_step nos passos citados e explique em linguagem de negócio.\n"
                            + "3. Termine com: apta / apta com ressalvas / bloqueada, e o que falta exercitar."
                            + DISCIPLINA));

    List<Prompt> all() {
        return prompts;
    }

    Prompt find(String name) {
        return prompts.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    static ObjectNode definition(Prompt p) {
        ObjectNode d = Json.obj().put("name", p.name()).put("title", p.title()).put("description", p.description());
        ArrayNode args = d.putArray("arguments");
        for (String[] a : p.arguments()) {
            args.addObject().put("name", a[0]).put("description", a[1]).put("required", Boolean.parseBoolean(a[2]));
        }
        return d;
    }

    private static String arg(JsonNode a, String name) {
        String v = a.path(name).asText("");
        return v.isBlank() ? "" : " (" + name + "=" + v + ")";
    }
}
