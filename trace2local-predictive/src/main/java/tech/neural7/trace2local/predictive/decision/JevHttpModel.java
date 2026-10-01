package tech.neural7.trace2local.predictive.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cliente do Jev (TypeSafe {@code POST /v1/systemone}) sem SDK — só
 * {@code java.net.http} e Jackson (zero dependência nova, compatível com AOT).
 *
 * <p>Corporativo por padrão: respeita o {@link ProxySelector} da JVM
 * ({@code https.proxyHost}), timeout curto, a chave só no cabeçalho
 * {@code Authorization} (nunca em log/exceção), e lotes limitados por
 * {@code max-questions}. As perguntas de um lote são avaliadas em paralelo pelo
 * próprio Jev — um lote ≈ uma latência (~0,2–0,7 s medidos).
 */
public final class JevHttpModel implements DecisionModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IntelligenceConfig cfg;
    private final HttpClient http;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private volatile String lastModelVersion;

    public JevHttpModel(IntelligenceConfig cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(3000, cfg.timeoutMs())))
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NEVER) // a chave não segue redirect
                .build();
    }

    @Override
    public String name() {
        return Answer.ENGINE_JEV;
    }

    @Override
    public Map<String, Answer> decide(Map<String, String> state, List<Question> questions) throws DecisionException {
        if (!cfg.hasKey()) {
            throw new DecisionException("auth", "sem chave do Jev");
        }
        Map<String, Answer> out = new LinkedHashMap<>();
        List<Question> batch = new ArrayList<>();
        for (Question q : questions) {
            batch.add(q);
            if (batch.size() >= cfg.maxQuestionsPerRequest()) {
                out.putAll(call(state, batch));
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            out.putAll(call(state, batch));
        }
        return out;
    }

    private Map<String, Answer> call(Map<String, String> state, List<Question> batch) throws DecisionException {
        ObjectNode body = requestBody(cfg.model(), state, batch);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(cfg.endpoint()))
                    .timeout(Duration.ofMillis(cfg.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("User-Agent", "trace2local-intelligence/0.1")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new DecisionException("bad-request", "falha ao montar a requisição ao Jev");
        }
        HttpResponse<String> response = null;
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 5) {
                    break;
                }
            } catch (java.io.IOException e) {
                lastError = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DecisionException("network", "interrompido");
            }
        }
        requests.incrementAndGet();
        if (response == null) {
            // a mensagem NÃO inclui a requisição (que carrega a chave no cabeçalho)
            throw new DecisionException("network", "Jev inalcançável: "
                    + (lastError != null ? lastError.getClass().getSimpleName() : "sem resposta"));
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new DecisionException("auth", "Jev recusou a chave (HTTP " + status + ")");
        }
        if (status == 402 || status == 429) {
            throw new DecisionException("budget", "Jev sem créditos/limite (HTTP " + status + ")");
        }
        if (status / 100 != 2) {
            throw new DecisionException("http-" + status, "Jev respondeu HTTP " + status);
        }
        try {
            return parse(MAPPER.readTree(response.body()), batch);
        } catch (DecisionException e) {
            throw e;
        } catch (Exception e) {
            throw new DecisionException("bad-response", "resposta do Jev ilegível");
        }
    }

    static ObjectNode requestBody(String model, Map<String, String> state, List<Question> batch) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ObjectNode stateNode = body.putObject("state");
        state.forEach(stateNode::put);
        ObjectNode qs = body.putObject("questions");
        for (Question q : batch) {
            ObjectNode qn = qs.putObject(q.id());
            qn.put("type", q.type().name().toLowerCase(java.util.Locale.ROOT));
            qn.put("instructions", q.instructions());
            if (q.type() == Question.Type.CHOICE) {
                ObjectNode criteria = qn.putObject("criteria");
                q.criteria().forEach(criteria::put);
            } else if (q.type() == Question.Type.SCORE) {
                var levels = qn.putArray("criteria");
                q.levels().forEach(levels::add);
            }
        }
        return body;
    }

    Map<String, Answer> parse(JsonNode root, List<Question> batch) throws DecisionException {
        JsonNode answers = root.path("answers");
        if (!answers.isObject()) {
            throw new DecisionException("bad-response", "resposta do Jev sem 'answers'");
        }
        String model = root.path("model").asText(cfg.model());
        lastModelVersion = model;
        inputTokens.addAndGet(root.path("usage").path("input_tokens").asLong(0));
        Map<String, Answer> out = new LinkedHashMap<>();
        for (Question q : batch) {
            JsonNode a = answers.path(q.id());
            if (a.isMissingNode() || a.isNull()) {
                continue;
            }
            out.put(q.id(), toAnswer(q, a, model));
        }
        return out;
    }

    static Answer toAnswer(Question q, JsonNode a, String model) {
        Map<String, Double> probs = new LinkedHashMap<>();
        a.path("probabilities").fields().forEachRemaining(e -> probs.put(e.getKey(), e.getValue().asDouble()));
        return switch (q.type()) {
            case NOUL -> {
                double p = clamp01(a.path("noul").asDouble(0.5));
                yield new Answer(q.id(), q.type(), null, p, Math.abs(p - 0.5) * 2.0, Map.of(), Answer.ENGINE_JEV, model, null);
            }
            case CHOICE -> {
                String choice = a.path("choice").asText(null);
                double conf = clamp01(a.path("confidence").asDouble(0));
                double pChoice = choice != null ? probs.getOrDefault(choice, conf) : 0;
                yield new Answer(q.id(), q.type(), choice, pChoice, conf, probs, Answer.ENGINE_JEV, model, null);
            }
            case SCORE -> {
                double score = a.path("score").asDouble(0);
                double conf = clamp01(a.path("confidence").asDouble(0));
                int idx = (int) Math.max(0, Math.min(q.levels().size() - 1, Math.round(score)));
                String level = q.levels().isEmpty() ? null : q.levels().get(idx);
                yield new Answer(q.id(), q.type(), level, score, conf, probs, Answer.ENGINE_JEV, model, null);
            }
        };
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    public long inputTokens() {
        return inputTokens.get();
    }

    public long requests() {
        return requests.get();
    }

    public String lastModelVersion() {
        return lastModelVersion;
    }
}
