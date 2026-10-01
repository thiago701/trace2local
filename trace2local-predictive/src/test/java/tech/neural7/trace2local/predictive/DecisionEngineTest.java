package tech.neural7.trace2local.predictive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.predictive.assistant.ExecutionAssistant;
import tech.neural7.trace2local.predictive.decision.Answer;
import tech.neural7.trace2local.predictive.decision.DecisionEngine;
import tech.neural7.trace2local.predictive.decision.DeterministicJevModel;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts;
import tech.neural7.trace2local.predictive.decision.IntelligenceConfig;
import tech.neural7.trace2local.predictive.decision.Question;
import tech.neural7.trace2local.predictive.decision.QuestionCatalog;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Motor de decisão (ADR-011): cascata cassete → Jev → determinístico, egresso
 * sanitizado, disjuntor, e REPRODUTIBILIDADE do caminho sem chave.
 */
class DecisionEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    void deterministicModeIsReproducibleBitForBit() {
        Execution e = Scenarios.orderFlow("det", Instant.parse("2026-09-30T10:00:00Z"), 4100);
        Map<String, String> glossary = Map.of("order-billing", "Consumidor que cobra o pedido e o marca como BILLED.");
        DecisionEngine engine = new DecisionEngine(IntelligenceConfig.from(k -> null));
        assertThat(engine.config().effectiveMode()).isEqualTo(IntelligenceConfig.Mode.DETERMINISTIC);
        String a = new ExecutionAssistant(engine).analyze(e, List.of(), glossary).without(List.of("generatedAt", "stats")).toString();
        String b = new ExecutionAssistant(engine).analyze(e, List.of(), glossary).without(List.of("generatedAt", "stats")).toString();
        assertThat(a).isEqualTo(b);
        assertThat(a).contains("\"outcome\"").contains("respeitada");
    }

    @Test
    void acronymsInTheRuleAreNotTakenAsExpectedStates() {
        // achado na stack alvo (finance-pix): "API de iniciação de Pix (API Gateway → Lambda). Regra: …"
        // saía VIOLADA num Pix perfeito — "API" era lido como o estado que o fluxo deveria alcançar
        Execution e = Scenarios.orderFlow("acr", Instant.parse("2026-09-30T10:00:00Z"), 40);
        Map<String, String> glossary = Map.of(
                "OrderService", "API de pedidos (API Gateway → Lambda). Regra: todo pedido aceito deve ser publicado na fila order-events.",
                "order-billing", "Consumidor (Lambda via SQS) que cobra o pedido e o marca como BILLED.");
        DecisionEngine engine = new DecisionEngine(IntelligenceConfig.from(k -> null));
        JsonNode rules = MAPPER.valueToTree(new ExecutionAssistant(engine).analyze(e, List.of(), glossary)).path("executive").path("rules");
        Map<String, String> verdicts = new LinkedHashMap<>();
        rules.forEach(r -> verdicts.put(r.path("term").asText(), r.path("choice").asText()));
        assertThat(verdicts).containsEntry("OrderService", "respeitada").containsEntry("order-billing", "respeitada");
    }

    @Test
    void ruleAboutTheFailurePathIsNotViolatedWhenNothingFailed() {
        // achado na stack alvo: "falha no provedor … deve ficar registrada como FAILED" e "SPI fora do ar
        // faz a mensagem voltar para a fila" saíam VIOLADAS num fluxo sem nenhuma falha
        Execution e = Scenarios.orderFlow("fail-path", Instant.parse("2026-09-30T10:00:00Z"), 40);
        Map<String, String> glossary = Map.of(
                "order-billing", "Cobrança. Regra: falha no gateway de cobrança nunca desfaz o pedido — deve ficar registrada como FAILED.",
                "OrderService", "Cria o pedido. Gateway fora do ar faz a mensagem voltar para a fila; após 3 tentativas vai para a DLQ.");
        DecisionEngine engine = new DecisionEngine(IntelligenceConfig.from(k -> null));
        JsonNode rules = MAPPER.valueToTree(new ExecutionAssistant(engine).analyze(e, List.of(), glossary)).path("executive").path("rules");
        Map<String, String> verdicts = new LinkedHashMap<>();
        rules.forEach(r -> verdicts.put(r.path("term").asText(), r.path("choice").asText()));
        assertThat(verdicts.get("order-billing")).isEqualTo("inconclusiva");
        assertThat(verdicts.get("OrderService")).isNotEqualTo("violada");
    }

    @Test
    void liveJevAnswersWithSanitizedStateAndFallsBackOnAuthFailure() throws Exception {
        AtomicReference<JsonNode> lastBody = new AtomicReference<>();
        AtomicReference<String> lastAuth = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200);
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/systemone", ex -> {
            JsonNode body = MAPPER.readTree(ex.getRequestBody().readAllBytes());
            lastBody.set(body);
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            var answers = MAPPER.createObjectNode();
            body.path("questions").fields().forEachRemaining(q -> {
                var a = answers.putObject(q.getKey());
                String type = q.getValue().path("type").asText();
                a.put("type", type);
                switch (type) {
                    case "noul" -> a.put("noul", 0.97);
                    case "choice" -> {
                        String first = q.getValue().path("criteria").fieldNames().next();
                        a.put("choice", first).put("confidence", 0.95);
                        a.putObject("probabilities").put(first, 0.97);
                    }
                    default -> a.put("score", 1.0).put("confidence", 0.9);
                }
            });
            var root = MAPPER.createObjectNode();
            root.put("model", "jev-1.13.0");
            root.set("answers", answers);
            root.putObject("usage").put("input_tokens", 321);
            byte[] bytes = MAPPER.writeValueAsBytes(root);
            if (status.get() != 200) {
                ex.sendResponseHeaders(status.get(), -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        fake.start();
        try {
            String endpoint = "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1/systemone";
            Map<String, String> cfg = new LinkedHashMap<>();
            cfg.put("api-key", "apikey_test_0123456789abcdef0123456789");
            cfg.put("endpoint", endpoint);
            cfg.put("mode", "live");
            DecisionEngine engine = new DecisionEngine(IntelligenceConfig.from(cfg::get));

            Map<String, String> state = new LinkedHashMap<>();
            state.put("steps", "cliente maria@empresa.com.br chamou https://api.interno.empresa.com/v1 e https://api.interno.empresa.com/v2");
            Question q = Question.noul("q1", "test", "s", "The flow writes data.");
            Map<String, Answer> out = engine.ask(state, List.of(q), new DeterministicJevModel());

            assertThat(out.get("q1").engine()).isEqualTo(Answer.ENGINE_JEV);
            assertThat(lastAuth.get()).isEqualTo("Bearer apikey_test_0123456789abcdef0123456789");
            String sent = lastBody.get().path("state").path("steps").asText();
            assertThat(sent).doesNotContain("maria@empresa.com.br").doesNotContain("api.interno.empresa.com");
            // mesmo host ⇒ mesmo pseudônimo (o modelo ainda percebe igualdade)
            assertThat(sent).contains("https://<host-1>/v1").contains("https://<host-1>/v2");

            // chave recusada ⇒ determinístico + disjuntor aberto (sem martelar a API)
            status.set(401);
            Map<String, Answer> fallback = engine.ask(state, List.of(q), new DeterministicJevModel());
            assertThat(fallback.get("q1").engine()).isEqualTo(Answer.ENGINE_DETERMINISTIC);
            assertThat(engine.status().get("circuit")).isEqualTo("aberto");
            assertThat(engine.config().toString()).doesNotContain("apikey_test");
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void cassetteRecordsLiveAnswersAndReplaysThemOfflineDeterministically() throws Exception {
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        fake.createContext("/v1/systemone", ex -> {
            calls.incrementAndGet();
            JsonNode body = MAPPER.readTree(ex.getRequestBody().readAllBytes());
            var answers = MAPPER.createObjectNode();
            body.path("questions").fieldNames().forEachRemaining(id -> answers.putObject(id).put("type", "noul").put("noul", 0.12));
            var root = MAPPER.createObjectNode().put("model", "jev-1.13.0");
            root.set("answers", answers);
            byte[] bytes = MAPPER.writeValueAsBytes(root);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        fake.start();
        try {
            Path cassette = tmp.resolve("jev-cassette.jsonl");
            Map<String, String> rec = Map.of("api-key", "k-123456789", "mode", "record", "cassette", cassette.toString(),
                    "endpoint", "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1/systemone");
            Map<String, String> state = Map.of("steps", "step 1: [LAMBDA] order-processor — OK 35 ms");
            Question q = Question.noul("q", "test", "s", "The execution failed.");
            Answer recorded = new DecisionEngine(IntelligenceConfig.from(rec::get))
                    .ask(state, List.of(q), new DeterministicJevModel()).get("q");
            assertThat(recorded.engine()).isEqualTo(Answer.ENGINE_JEV);
            assertThat(java.nio.file.Files.readString(cassette, StandardCharsets.UTF_8))
                    .doesNotContain("order-processor"); // só hash + resposta: nenhum dado persiste

            // replay: sem chave, sem rede — mesma resposta
            Map<String, String> rep = Map.of("mode", "replay", "cassette", cassette.toString());
            int before = calls.get();
            Answer replayed = new DecisionEngine(IntelligenceConfig.from(rep::get))
                    .ask(state, List.of(q), new DeterministicJevModel()).get("q");
            assertThat(replayed.engine()).isEqualTo(Answer.ENGINE_REPLAY);
            assertThat(replayed.value()).isEqualTo(recorded.value());
            assertThat(calls.get()).isEqualTo(before);
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void factsAreNeverAskedToTheModel() {
        Execution e = Scenarios.orderFlow("facts", Instant.now(), 30);
        ExecutionFacts f = ExecutionFacts.of(e, List.of(), Map.of());
        List<Question> qs = QuestionCatalog.build(f, QuestionCatalog.Limits.DEFAULT);
        List<String> factual = new ArrayList<>();
        for (Question q : qs) {
            if (QuestionCatalog.fact(q, f) != null) {
                factual.add(q.family());
            }
        }
        // tipo de nó estrutural, dados alterados e consumo assíncrono são FATOS
        assertThat(factual).contains(QuestionCatalog.EXEC_DATA, QuestionCatalog.EXEC_ASYNC, QuestionCatalog.NODE_ROLE,
                QuestionCatalog.EXEC_OUTCOME);
    }
}
