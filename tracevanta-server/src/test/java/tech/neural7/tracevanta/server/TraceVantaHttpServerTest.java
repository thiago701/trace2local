package tech.neural7.tracevanta.server;

import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.SpanStartEvent;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;
import tech.neural7.tracevanta.model.NodeKind;
import tech.neural7.tracevanta.spi.EndpointDescriptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TraceVantaHttpServerTest {

    @Test
    void servesUiAndRestWithSecurityHeaders() throws Exception {
        TraceVantaConfig cfg = TraceVantaConfig.builder().port(0).build();
        try (TraceVantaPipeline pipeline = TraceVantaPipeline.start(cfg)) {
            TraceVantaHttpServer server = TraceVantaHttpServer.builder(cfg, pipeline)
                    .meta(() -> TraceVantaMeta.embedded("order-service"))
                    .endpoints(() -> List.of(EndpointDescriptor.of("post:/orders", "POST", "/orders", "OrderController.create")))
                    .build();
            server.start();
            try {
                String base = "http://127.0.0.1:" + server.port();

                // UI servida com CSP restritivo (SPEC §8.1)
                HttpResponse<String> index = httpGet(base + "/tracevanta/");
                assertThat(index.statusCode()).isEqualTo(200);
                assertThat(index.body()).contains("TRACEVANTA");
                assertThat(index.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(
                        csp -> assertThat(csp).startsWith("default-src 'self'"));
                assertThat(index.headers().firstValue("X-Frame-Options")).hasValue("DENY");

                // meta
                HttpResponse<String> meta = httpGet(base + "/tracevanta/api/meta");
                assertThat(meta.statusCode()).isEqualTo(200);
                assertThat(meta.body()).contains("order-service").contains("embedded");

                // endpoints
                HttpResponse<String> endpoints = httpGet(base + "/tracevanta/api/endpoints");
                assertThat(endpoints.body()).contains("post:/orders").contains("/orders");

                // health
                HttpResponse<String> health = httpGet(base + "/tracevanta/api/health");
                assertThat(health.body()).contains("\"status\":\"ok\"").contains("\"connectedClients\":0");

                // execução inexistente ⇒ 404
                HttpResponse<String> missing = httpGet(base + "/tracevanta/api/executions/TV-99999");
                assertThat(missing.statusCode()).isEqualTo(404);

                // endurecimento de headers (ADR-007/§8.1): API nunca cacheável
                assertThat(missing.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
                assertThat(missing.headers().firstValue("Cache-Control")).hasValue("no-store");
                assertThat(missing.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
            } finally {
                server.close();
            }
        }
    }

    @Test
    void stationTokenProtectsExtraIngestRoutes() throws Exception {
        TraceVantaConfig cfg = TraceVantaConfig.builder().port(0).stationToken("sekrit-token").build();
        try (TraceVantaPipeline pipeline = TraceVantaPipeline.start(cfg)) {
            TraceVantaHttpServer server = TraceVantaHttpServer.builder(cfg, pipeline)
                    .extraRoute("/v1/test", exchange -> {
                        byte[] ok = "{\"accepted\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, ok.length);
                        try (var out = exchange.getResponseBody()) {
                            out.write(ok);
                        }
                    })
                    .build();
            server.start();
            try {
                String url = "http://127.0.0.1:" + server.port() + "/v1/test";

                // sem token ⇒ 401 com desafio Bearer
                HttpResponse<String> noAuth = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(noAuth.statusCode()).isEqualTo(401);
                assertThat(noAuth.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");

                // token errado ⇒ 401
                HttpResponse<String> wrong = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Authorization", "Bearer wrong")
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(wrong.statusCode()).isEqualTo(401);

                // token correto ⇒ delega (200)
                HttpResponse<String> ok = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Authorization", "Bearer sekrit-token")
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(ok.statusCode()).isEqualTo(200);
                assertThat(ok.body()).contains("\"accepted\":1");
            } finally {
                server.close();
            }
        }
    }

    @Test
    void executeDelegatesToLauncherAndReturns202() throws Exception {
        TraceVantaConfig cfg = TraceVantaConfig.builder().port(0).build();
        try (TraceVantaPipeline pipeline = TraceVantaPipeline.start(cfg)) {
            TraceVantaHttpServer server = TraceVantaHttpServer.builder(cfg, pipeline)
                    .launcher(req -> new ExecutionLauncher.LaunchResult("TV-88291", "4bf92f3577b34da6a3ce929d0e0e4736"))
                    .build();
            server.start();
            try {
                HttpResponse<String> res = httpPost("http://127.0.0.1:" + server.port() + "/tracevanta/api/execute",
                        "{\"endpointId\":\"post:/orders\",\"body\":{\"customerId\":\"C-1\"}}");
                assertThat(res.statusCode()).isEqualTo(202);
                assertThat(res.body()).contains("TV-88291");
            } finally {
                server.close();
            }
        }
    }

    @Test
    void streamCarriesLiveExecutionEvents() throws Exception {
        TraceVantaConfig cfg = TraceVantaConfig.builder().port(0).build();
        try (TraceVantaPipeline pipeline = TraceVantaPipeline.start(cfg)) {
            TraceVantaHttpServer server = TraceVantaHttpServer.builder(cfg, pipeline).build();
            server.start();
            try {
                CountDownLatch received = new CountDownLatch(1);
                CountDownLatch ready = new CountDownLatch(1);
                HttpClient client = HttpClient.newHttpClient();
                var future = client.sendAsync(HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + server.port() + "/tracevanta/api/stream"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofLines())
                        .thenAccept(response -> response.body().forEach(line -> {
                            if (line.contains(": ready")) {
                                ready.countDown();
                            }
                            if (line.contains("event: execution.started")) {
                                received.countDown();
                            }
                        }));

                // espera o handshake : ready — sem depender de timing do servidor
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                pipeline.buffer().offer(new SpanStartEvent("trace-stream-1", "root", null, "TV-00099", null,
                        NodeKind.HTTP_SERVER, "POST /orders", Map.of(), Instant.now()));
                pipeline.buffer().offer(new tech.neural7.tracevanta.internal.SpanEndEvent(
                        "trace-stream-1", "root", null, "TV-00099", null, NodeKind.HTTP_SERVER, "POST /orders",
                        Map.of(), Instant.now(), Instant.now().plusMillis(10), false, null, null, null, List.of(), false));

                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                future.cancel(true);
            } finally {
                server.close();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static HttpResponse<String> httpGet(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> httpPost(String url, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
