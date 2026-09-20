package tech.neural7.tracevanta.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.internal.JsonSupport;
import tech.neural7.tracevanta.server.ExecutionLauncher;
import tech.neural7.tracevanta.spi.EndpointDescriptor;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLauncherTest {

    private HttpServer target;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() throws Exception {
        GlobalOpenTelemetry.resetForTest();
        // hermético: o registro estático do TraceVantaOtel vaza entre testes do mesmo fork
        tech.neural7.tracevanta.otel.TraceVantaOtel.register(null);
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(io.opentelemetry.context.propagation.ContextPropagators.create(
                        io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
                .build();
        GlobalOpenTelemetry.set(sdk);
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        target.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    @AfterEach
    void tearDown() {
        if (target != null) {
            target.stop(0);
        }
        sdk.getSdkTracerProvider().close();
        GlobalOpenTelemetry.resetForTest();
        tech.neural7.tracevanta.otel.TraceVantaOtel.register(null);
    }

    @Test
    void dispatchesWithTraceparentAndBodyToTheApp() throws Exception {
        AtomicReference<String> traceparent = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        target.createContext("/orders", exchange -> {
            traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"orderId\":\"88291\"}".getBytes();
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        target.start();

        EndpointDescriptor endpoint = new EndpointDescriptor("post:/orders", "POST", "/orders",
                "OrderController.create", null, null);
        RequestLauncher launcher = new RequestLauncher(() -> List.of(endpoint), target.getAddress().getPort());
        JsonNode body = JsonSupport.MAPPER.readTree("{\"customerId\":\"C-1\"}");

        ExecutionLauncher.LaunchResult result = launcher.launch(
                new ExecutionLauncher.ExecuteRequest("post:/orders", Map.of(), body, Map.of()));

        assertThat(result.executionId()).matches("TV-\\d{5}");
        assertThat(result.traceId()).hasSize(32);
        assertThat(traceparent.get()).isNotNull().startsWith("00-").contains(result.traceId());
        assertThat(receivedBody.get()).contains("C-1");
    }

    @Test
    void rejectsUnknownEndpointIdBeforeAnyNetwork() {
        RequestLauncher launcher = new RequestLauncher(List::of, 8080);
        assertThatThrownBy(() -> launcher.launch(
                new ExecutionLauncher.ExecuteRequest("post:/evil", Map.of(), null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catálogo");
    }

    @Test
    void resolvesPathVariableTemplates() {
        assertThat(RequestLauncher.resolvePath("/orders/{id}", Map.of("id", "88291")))
                .isEqualTo("/orders/88291");
        assertThat(RequestLauncher.resolvePath("/orders/{id}/items/{itemId}",
                Map.of("id", "1", "itemId", "2"))).isEqualTo("/orders/1/items/2");
    }

    @Test
    void refusesNonLoopbackTargets() {
        // o alvo é construído a partir do host configurado — nunca do cliente — e a
        // resolução é re-verificada antes do envio (SPEC §8.2, defesa contra rebinding)
        EndpointDescriptor endpoint = new EndpointDescriptor("get:/meta", "GET", "/meta", "x", null, null);
        RequestLauncher launcher = new RequestLauncher(() -> List.of(endpoint), 80, "169.254.169.254");
        assertThatThrownBy(() -> launcher.launch(
                new ExecutionLauncher.ExecuteRequest("get:/meta", Map.of(), null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }
}
