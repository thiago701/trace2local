package tech.neural7.trace2local.mocks;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;
import tech.neural7.trace2local.mocks.runtime.MockJournal;
import tech.neural7.trace2local.otel.Trace2LocalAttributes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Servidor embutido: casamento, variações por predicado, falhas de rede, near-miss, journal e ciclo de vida. */
class EmbeddedMockServerTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @TempDir
    Path dir;
    private MockConnectWorker worker;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach
    void setUp() {
        worker = new MockConnectWorker(MockConnectWorker.Settings.loopback(0).withDataDir(dir)
                .withStateFile(dir.resolve("state.json")).withEnv(k -> k.equals("WM_TOKEN") ? "tk" : null), List::of);
        worker.start();
    }

    @AfterEach
    void tearDown() {
        worker.close();
    }

    private Map<String, String> antifraude() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("target", "antifraude.parceiro:8080");
        cfg.put("api.name", "Antifraude");
        cfg.put("source", "inline");
        cfg.put("source.stubs", """
                [{"method":"POST","path":"/v1/score","body":{"decision":"APPROVED","score":120}},
                 {"method":"GET","path":"/v1/clients/{cpf}/limits","body":{"daily":5000}}]""");
        cfg.put("transforms", "negado,eco");
        cfg.put("transforms.negado.type", "set-field");
        cfg.put("transforms.negado.pointer", "/decision");
        cfg.put("transforms.negado.value", "DENIED");
        cfg.put("transforms.negado.predicate", "pedido");
        cfg.put("transforms.eco.type", "template");
        cfg.put("transforms.eco.body", "{\"cliente\":\"{{request.path.2}}\",\"daily\":{{response/daily}}}");
        cfg.put("transforms.eco.predicate", "limites");
        cfg.put("predicates", "pedido,limites");
        cfg.put("predicates.pedido.type", "header-matches");
        cfg.put("predicates.pedido.name", "X-Cenario");
        cfg.put("predicates.pedido.regex", "negado");
        cfg.put("predicates.limites.type", "path-matches");
        cfg.put("predicates.limites.pattern", "/v1/clients/{cpf}/limits");
        return cfg;
    }

    private String base(String binding) {
        return "http://127.0.0.1:" + worker.port() + "/" + binding;
    }

    private HttpResponse<String> post(String url, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesStubsAppliesVariationsByPredicateAndMarksTheResponse() throws Exception {
        var info = worker.create("antifraude", antifraude());
        assertThat(info.status().state()).isEqualTo("RUNNING");
        assertThat(info.status().endpoint()).isEqualTo(base("antifraude"));

        HttpResponse<String> normal = post(base("antifraude") + "/v1/score", "{\"amount\":10}", "traceparent", TRACEPARENT);
        assertThat(normal.statusCode()).isEqualTo(200);
        assertThat(JsonSupport.parse(normal.body()).path("decision").asText()).isEqualTo("APPROVED");
        assertThat(normal.headers().firstValue(Trace2LocalAttributes.MOCK_HEADER)).hasValueSatisfying(v ->
                assertThat(v).contains("binding=antifraude").doesNotContain("variation="));

        HttpResponse<String> negado = post(base("antifraude") + "/v1/score", "{}", "X-Cenario", "negado");
        assertThat(JsonSupport.parse(negado.body()).path("decision").asText()).isEqualTo("DENIED");
        assertThat(JsonSupport.parse(negado.body()).path("score").asInt()).isEqualTo(120); // resto intacto
        assertThat(negado.headers().firstValue(Trace2LocalAttributes.MOCK_HEADER)).hasValueSatisfying(v ->
                assertThat(v).contains("variation=negado"));

        HttpResponse<String> limites = http.send(HttpRequest.newBuilder(URI.create(base("antifraude")
                + "/v1/clients/12345678909/limits")).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode eco = JsonSupport.parse(limites.body());
        assertThat(eco.path("cliente").asText()).isEqualTo("12345678909");
        assertThat(eco.path("daily").asInt()).isEqualTo(5000);

        // o journal é gravado logo DEPOIS de a resposta sair (o cliente pode ler antes): espera curta
        List<MockJournal.Entry> journal = awaitJournal("antifraude", 3);
        assertThat(journal).hasSize(3);
        assertThat(journal.get(2).traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736"); // liga ao trace
        assertThat(journal.get(1).applied()).containsExactly("negado");
    }

    @Test
    void passthroughKeepsTheRealApiAndAppliesTheVariationOnlyWhenAsked() throws Exception {
        com.sun.net.httpserver.HttpServer real = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        real.createContext("/v1/score", ex -> {
            byte[] b = "{\"decision\":\"APPROVED\",\"score\":112}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        real.start();
        try {
            Map<String, String> cfg = new LinkedHashMap<>();
            cfg.put("target", "127.0.0.1:" + real.getAddress().getPort());
            cfg.put("source", "proxy");
            cfg.put("transforms", "sem-decisao");
            cfg.put("transforms.sem-decisao.type", "remove-field");
            cfg.put("transforms.sem-decisao.pointer", "/decision");
            cfg.put("transforms.sem-decisao.predicate", "pedido");
            cfg.put("predicates", "pedido");
            cfg.put("predicates.pedido.type", "header-matches");
            cfg.put("predicates.pedido.name", "baggage");
            cfg.put("predicates.pedido.regex", ".*t2l\\.mock=sem-decisao.*");
            worker.create("antifraude", cfg);

            HttpResponse<String> realOne = post(base("antifraude") + "/v1/score", "{}");
            assertThat(JsonSupport.parse(realOne.body()).path("decision").asText()).isEqualTo("APPROVED");
            assertThat(realOne.headers().firstValue(Trace2LocalAttributes.MOCK_HEADER).orElse("")).contains("passthrough=true")
                    .doesNotContain("variation=");
            HttpResponse<String> varied = post(base("antifraude") + "/v1/score", "{}", "baggage", "t2l.mock=sem-decisao");
            JsonNode body = JsonSupport.parse(varied.body());
            assertThat(body.has("decision")).isFalse();
            assertThat(body.path("score").asInt()).isEqualTo(112); // resto da resposta REAL intacto
        } finally {
            real.stop(0);
        }
    }

    @Test
    void unmatchedRequestExplainsTheNearMisses() throws Exception {
        worker.create("antifraude", antifraude());
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base("antifraude") + "/v1/scores")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(404);
        JsonNode body = JsonSupport.parse(r.body());
        assertThat(body.path("nearMisses").get(0).path("stubId").asText()).isEqualTo("inline-0");
        assertThat(body.path("nearMisses").get(0).path("differences").toString()).contains("método GET ≠ POST");
        assertThat(awaitJournal("antifraude", 1).get(0).outcome()).isEqualTo("unmatched");
    }

    @Test
    void transientFailureOnlyOnTheFirstCallValidatesRetry() throws Exception {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("target", "spi.bacen:8080");
        cfg.put("source", "inline");
        cfg.put("source.stubs", "[{\"method\":\"POST\",\"path\":\"/spi/settlements\",\"status\":201,\"body\":{\"status\":\"SETTLED\"}}]");
        cfg.put("transforms", "falha");
        cfg.put("transforms.falha.type", "set-status");
        cfg.put("transforms.falha.code", "503");
        cfg.put("transforms.falha.predicate", "primeira");
        cfg.put("predicates", "primeira");
        cfg.put("predicates.primeira.type", "call-count");
        cfg.put("predicates.primeira.calls", "1");
        worker.create("spi", cfg);

        assertThat(post(base("spi") + "/spi/settlements", "{}").statusCode()).isEqualTo(503);
        assertThat(post(base("spi") + "/spi/settlements", "{}").statusCode()).isEqualTo(201);
        worker.restart("spi"); // restart zera o cenário
        assertThat(post(base("spi") + "/spi/settlements", "{}").statusCode()).isEqualTo(503);
    }

    @Test
    void networkFaultsBreakTheConnectionLikeARealOutage() throws Exception {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("target", "dict.bacen:8080");
        cfg.put("source", "inline");
        cfg.put("source.stubs", "[{\"path\":\"/entries/{key}\",\"body\":{\"key\":\"x\"}}]");
        cfg.put("transforms", "queda");
        cfg.put("transforms.queda.type", "fault");
        cfg.put("transforms.queda.kind", "connection-reset");
        worker.create("dict", cfg);
        assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(URI.create(base("dict") + "/entries/abc")).build(),
                HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);

        cfg.put("transforms.queda.kind", "empty-response");
        worker.put("dict", cfg);
        HttpResponse<String> empty = http.send(HttpRequest.newBuilder(URI.create(base("dict") + "/entries/abc")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(empty.body()).isEmpty();

        cfg.put("transforms.queda.type", "latency");
        cfg.remove("transforms.queda.kind");
        cfg.put("transforms.queda.ms", "300");
        worker.put("dict", cfg);
        long t0 = System.nanoTime();
        http.send(HttpRequest.newBuilder(URI.create(base("dict") + "/entries/abc")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(280);
    }

    @Test
    void pauseResumeAndPersistenceWithoutPlaintextSecrets() throws Exception {
        worker.create("antifraude", antifraude());
        worker.pause("antifraude");
        HttpResponse<String> paused = http.send(HttpRequest.newBuilder(URI.create(base("antifraude") + "/v1/score"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(paused.statusCode()).isEqualTo(404);
        assertThat(worker.routes()).isEmpty();
        worker.resume("antifraude");
        assertThat(worker.routes()).singleElement().satisfies(r -> {
            assertThat(r.target()).isEqualTo("antifraude.parceiro:8080");
            assertThat(r.endpoint()).isEqualTo(base("antifraude"));
        });

        // binding com segredo literal: mascarado na API e fora do arquivo de estado
        Map<String, String> wm = new LinkedHashMap<>();
        wm.put("target", "dict:8080");
        wm.put("source", "inline");
        wm.put("source.stubs", "[{\"path\":\"/x\"}]");
        wm.put("sink", "wiremock");
        wm.put("sink.url", "http://127.0.0.1:1");
        wm.put("sink.auth.token", "literal-secret");
        var info = worker.create("wm", wm);
        assertThat(info.status().state()).isEqualTo("FAILED");               // WireMock fora do ar
        assertThat(info.status().trace()).contains("WireMock inacessível");  // trace acionável
        assertThat(info.config().get("sink.auth.token")).isEqualTo("[hidden]");
        assertThat(Files.readString(dir.resolve("state.json"))).doesNotContain("literal-secret").contains("antifraude");

        worker.close();
        worker = new MockConnectWorker(MockConnectWorker.Settings.loopback(0).withDataDir(dir)
                .withStateFile(dir.resolve("state.json")), List::of);
        worker.start();
        assertThat(worker.list()).extracting(MockConnectWorker.BindingStatus::name).contains("antifraude", "wm");
        assertThat(worker.get("antifraude").orElseThrow().status().state()).isEqualTo("RUNNING");
    }

    @Test
    void dataDirectoryIsAJail() throws Exception {
        Files.createDirectories(dir.resolve("mappings"));
        Files.writeString(dir.resolve("mappings/dict.json"), """
                {"mappings":[{"request":{"method":"GET","urlPathTemplate":"/api/v2/entries/{key}",
                  "headers":{"X-Ispb":{"equalTo":"60701190"}}},
                  "response":{"status":200,"jsonBody":{"key":"maria@example.com"},"fixedDelayMilliseconds":5}}]}""");
        Map<String, String> ok = new LinkedHashMap<>();
        ok.put("target", "dict.bacen:8080");
        ok.put("source", "inline");
        ok.put("source.dir", "mappings");
        assertThat(worker.create("dict", ok).status().state()).isEqualTo("RUNNING");
        assertThat(http.send(HttpRequest.newBuilder(URI.create(base("dict") + "/api/v2/entries/k1"))
                .header("X-Ispb", "60701190").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        Map<String, String> escape = new LinkedHashMap<>(ok);
        escape.put("source.dir", "../../etc");
        var failed = worker.create("fuga", escape);
        assertThat(failed.status().state()).isEqualTo("FAILED");
        assertThat(failed.status().trace()).contains("TRACE2LOCAL_MOCKS_DIR");
    }

    private List<MockJournal.Entry> awaitJournal(String binding, int size) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        List<MockJournal.Entry> entries = worker.journal().list(binding, 50);
        while (entries.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(10);
            entries = worker.journal().list(binding, 50);
        }
        return entries;
    }
}
