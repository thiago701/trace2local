package tech.neural7.trace2local.otel;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Chamadas de saída (APIs externas) viram nós HTTP_CLIENT completos — sem agente. */
class Trace2LocalHttpTest {

    private HttpServer server;
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final AtomicReference<String> seenTraceparent = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        Trace2LocalOtel.register(sdk);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/entries", ex -> {
            seenTraceparent.set(ex.getRequestHeaders().getFirst("traceparent"));
            byte[] out = "{\"key\":\"maria@example.com\",\"account\":{\"ispb\":\"60701190\",\"number\":\"12345-6\"},\"token\":\"abc123\"}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/spi/settlements", ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void outgoingCallBecomesAClientNodeWithPropagationAndRedactedPayload() throws Exception {
        HttpClient client = Trace2LocalHttp.instrument(HttpClient.newHttpClient(), "DICT (BACEN)");
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/v2/entries/maria%40example.com?ispb=1"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getName()).isEqualTo("DICT (BACEN) /api/v2/entries/{id}");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(OtelAttributeNames.URL_FULL)))
                .isEqualTo(base() + "/api/v2/entries/{id}?ispb=…");
        assertThat(seenTraceparent.get()).contains(span.getTraceId()).contains(span.getSpanId());
        String payload = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Trace2LocalAttributes.PAYLOAD_RESPONSE));
        assertThat(payload).contains("60701190").doesNotContain("abc123").doesNotContain("maria@example.com");
        assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void serverErrorMarksTheNodeRedAndKeepsTheRequestBodyFlowingIntact() throws Exception {
        HttpClient client = Trace2LocalHttp.instrument(HttpClient.newHttpClient(), "SPI (BACEN)");
        String body = "{\"endToEndId\":\"E6070119020261001\",\"amount\":150.00,\"password\":\"segredo\"}";
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(base() + "/spi/settlements"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(seenBody.get()).isEqualTo(body); // o tee não altera o que vai para a rede
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("HTTP 503");
        String req = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Trace2LocalAttributes.PAYLOAD_REQUEST));
        assertThat(req).contains("E6070119020261001").doesNotContain("segredo");
    }

    @Test
    void routeHidesIdentifiersFromTheLabel() {
        assertThat(Trace2LocalHttp.routeOf(URI.create("http://x/accounts/12345678909/limits"))).isEqualTo("/accounts/{id}/limits");
        assertThat(Trace2LocalHttp.routeOf(URI.create("http://x/v1/score"))).isEqualTo("/v1/score");
        assertThat(Trace2LocalHttp.routeOf(URI.create("http://x/tx/6f1c2d3e-0000-4000-8000-000000000001"))).isEqualTo("/tx/{id}");
        assertThat(Trace2LocalHttp.instrument(Trace2LocalHttp.instrument(HttpClient.newHttpClient())))
                .isInstanceOf(Trace2LocalHttp.Traced.class);
    }
}
