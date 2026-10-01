package tech.neural7.trace2local.predictive.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.internal.TextRedactor;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Insight;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Explicação/hipóteses de um insight (ADR-013 §1 — "LLM só quando há necessidade
 * real"). O PADRÃO é determinístico ({@link #template}): roteiro de investigação
 * montado a partir da própria evidência. Um LLM (endpoint compatível com
 * OpenAI {@code /v1/chat/completions} — ex.: Ollama/LM Studio LOCAIS) só é usado
 * quando configurado E sob demanda do dev (botão "Explicar com LLM"), para UM
 * insight, com payload restrito a título/observação/evidências já redigidos.
 * Endpoint fora do loopback exige {@code TRACE2LOCAL_LLM_ALLOW_EXTERNAL=true}.
 */
public final class InvestigationExplainer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final boolean allowed;
    private final HttpClient http;

    public InvestigationExplainer(String endpoint, String model, String apiKey, boolean allowExternal) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? null : endpoint.trim();
        this.model = model == null || model.isBlank() ? "llama3.2" : model.trim();
        this.apiKey = apiKey;
        boolean loopback = this.endpoint != null && (this.endpoint.startsWith("http://localhost")
                || this.endpoint.startsWith("http://127.0.0.1") || this.endpoint.startsWith("http://[::1]"));
        this.allowed = this.endpoint != null && (loopback || (allowExternal && this.endpoint.startsWith("https://")));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static InvestigationExplainer fromEnvironment() {
        return new InvestigationExplainer(env("TRACE2LOCAL_LLM_ENDPOINT"), env("TRACE2LOCAL_LLM_MODEL"),
                env("TRACE2LOCAL_LLM_API_KEY"), "true".equalsIgnoreCase(env("TRACE2LOCAL_LLM_ALLOW_EXTERNAL")));
    }

    public boolean llmAvailable() {
        return allowed;
    }

    public String llmDescription() {
        if (endpoint == null) {
            return "desligado (defina TRACE2LOCAL_LLM_ENDPOINT — ex.: Ollama local)";
        }
        return allowed ? model + " @ " + URI.create(endpoint).getHost() : "bloqueado: endpoint externo sem TRACE2LOCAL_LLM_ALLOW_EXTERNAL=true";
    }

    /** Resultado da explicação, com procedência. */
    public record Explanation(String text, String engine, String model) {}

    /** Explica: LLM se pedido e permitido; senão o roteiro determinístico. */
    public Explanation explain(Insight i, boolean useLlm) {
        if (useLlm && allowed) {
            try {
                return new Explanation(callLlm(i), "llm", model);
            } catch (Exception e) {
                return new Explanation(template(i) + "\n\n(LLM indisponível: " + e.getClass().getSimpleName()
                        + " — exibido o roteiro determinístico)", "template", "t2l-template-1.0");
            }
        }
        return new Explanation(template(i), "template", "t2l-template-1.0");
    }

    /** Roteiro de investigação determinístico (sempre disponível, sem rede). */
    public static String template(Insight i) {
        StringBuilder sb = new StringBuilder();
        sb.append("O QUE FOI OBSERVADO (fato)\n").append(i.observation()).append("\n\n");
        sb.append("EVIDÊNCIAS\n");
        for (Evidence e : i.evidence()) {
            sb.append("• ").append(e.label()).append(": ").append(e.value());
            if (e.ref() != null && e.ref().file() != null) {
                sb.append(" (").append(e.ref().file()).append(e.ref().line() > 0 ? ":" + e.ref().line() : "").append(')');
            }
            sb.append('\n');
        }
        if (i.correlation() != null) {
            sb.append("\nCORRELAÇÃO\n").append(i.correlation()).append('\n');
        }
        if (i.hypothesis() != null) {
            sb.append("\nHIPÓTESE (").append(i.confidenceBand()).append(", ").append(Math.round(i.confidence() * 100))
                    .append("%) — não é fato\n").append(i.hypothesis()).append('\n');
        }
        sb.append("\nONDE INVESTIGAR\n");
        int n = 1;
        for (String r : i.recommendations()) {
            sb.append(n++).append(". ").append(r).append('\n');
        }
        return sb.toString().trim();
    }

    private String callLlm(Insight i) throws Exception {
        StringBuilder facts = new StringBuilder();
        facts.append("Title: ").append(i.title()).append('\n')
                .append("Category: ").append(i.category().label()).append(" | severity ").append(i.severity())
                .append(" | confidence ").append(Math.round(i.confidence() * 100)).append("%\n")
                .append("Observation (fact): ").append(i.observation()).append('\n');
        for (Evidence e : i.evidence()) {
            facts.append("Evidence: ").append(e.label()).append(" = ").append(e.value()).append('\n');
        }
        if (i.correlation() != null) {
            facts.append("Correlation: ").append(i.correlation()).append('\n');
        }
        String prompt = TextRedactor.redact(facts.toString());
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.2);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                "You are a senior observability engineer. Answer in Brazilian Portuguese, in at most 8 short bullet points. "
                        + "Separate clearly: FATOS (only from the evidence), HIPÓTESES (mark as hypotheses) and PRÓXIMOS PASSOS. "
                        + "Never invent numbers that are not in the evidence.");
        messages.addObject().put("role", "user").put("content", prompt);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint.endsWith("/") ? endpoint + "v1/chat/completions"
                        : endpoint + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        JsonNode root = MAPPER.readTree(res.body());
        String text = root.path("choices").path(0).path("message").path("content").asText("");
        if (text.isBlank()) {
            throw new IllegalStateException("resposta vazia");
        }
        return "[hipóteses geradas por LLM — " + model + "; confira contra as evidências]\n" + text.trim();
    }

    private static String env(String k) {
        String v = System.getProperty(k.toLowerCase(Locale.ROOT).replace('_', '.'), System.getenv(k));
        return v == null || v.isBlank() ? null : v;
    }
}
