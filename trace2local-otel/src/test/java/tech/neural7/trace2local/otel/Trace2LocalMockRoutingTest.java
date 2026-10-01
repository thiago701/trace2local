package tech.neural7.trace2local.otel;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
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

/** Roteamento do cliente: chamada ao host do parceiro cai no mock e o nó fica marcado como simulado. */
class Trace2LocalMockRoutingTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private HttpServer station;
    private HttpServer mock;
    private final AtomicReference<String> routesJson = new AtomicReference<>("[]");
    private final AtomicReference<String> seenPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        Trace2LocalOtel.register(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build());
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", ex -> {
            seenPath.set(ex.getRequestURI().toString());
            byte[] body = "{\"decision\":\"DENIED\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add(Trace2LocalAttributes.MOCK_HEADER, "binding=mock-antifraude; stub=avaliarRisco; variation=v-negado");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        mock.start();
        station = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        station.createContext("/t2lingest/v1/mock-routes", ex -> {
            byte[] body = routesJson.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        station.start();
    }

    @AfterEach
    void tearDown() {
        station.stop(0);
        mock.stop(0);
    }

    @Test
    void callToThePartnerIsRoutedToTheMockAndMarkedAsSimulated() throws Exception {
        routesJson.set("[{\"binding\":\"mock-antifraude\",\"target\":\"antifraude.parceiro:8080\",\"endpoint\":\"http://127.0.0.1:"
                + mock.getAddress().getPort() + "/mock-antifraude\"}]");
        MockRouter router = new Trace2LocalMockRouting(URI.create("http://127.0.0.1:" + station.getAddress().getPort()), null);
        HttpClient client = Trace2LocalHttp.instrument(HttpClient.newHttpClient(),
                Trace2LocalHttp.Options.defaults().withPeerService("Antifraude").withMockRouter(router));

        // o host do parceiro nem existe: sem o roteamento, a chamada falharia
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://antifraude.parceiro:8080/api/v1/score?x=1"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.body()).contains("DENIED");
        assertThat(seenPath.get()).isEqualTo("/mock-antifraude/api/v1/score?x=1");
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey(OtelAttributeNames.SERVER_ADDRESS)))
                .isEqualTo("antifraude.parceiro"); // a dependência lógica continua a mesma
        assertThat(span.getAttributes().get(AttributeKey.booleanKey(Trace2LocalAttributes.MOCK_ROUTED))).isTrue();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(Trace2LocalAttributes.MOCK))).contains("variation=v-negado");
    }

    @Test
    void stationDownOrNoRouteMeansTheRealApiIsCalled() throws Exception {
        MockRouter down = new Trace2LocalMockRouting(URI.create("http://127.0.0.1:1"), null);
        assertThat(down.route(URI.create("http://antifraude.parceiro:8080/x"))).isEmpty();
        MockRouter noRoute = new Trace2LocalMockRouting(URI.create("http://127.0.0.1:" + station.getAddress().getPort()), null);
        assertThat(noRoute.route(URI.create("http://antifraude.parceiro:8080/x"))).isEmpty();
        assertThat(MockRouter.none().route(URI.create("http://x/y"))).isEmpty();
    }
}
