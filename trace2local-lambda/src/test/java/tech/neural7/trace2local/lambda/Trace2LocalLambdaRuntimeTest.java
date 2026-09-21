package tech.neural7.trace2local.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.config.Trace2LocalConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Trace2LocalLambdaRuntimeTest {

    @Test
    void flushesSynchronouslyAtTheEndOfInvocation() throws Exception {
        List<SpanData> exported = new ArrayList<>();
        Trace2LocalLambdaRuntime runtime = Trace2LocalLambdaRuntime.create(
                Trace2LocalConfig.defaults(), new InMemoryExporter(exported));

        Context ctx = mock(Context.class);
        when(ctx.getFunctionName()).thenReturn("order-consumer");
        when(ctx.getAwsRequestId()).thenReturn("req-1");

        String result = runtime.around(ctx, () -> {
            assertThat(exported).isEmpty(); // span ainda aberto — telemetria não saiu
            return "done";
        });

        assertThat(result).isEqualTo("done");
        // flush SÍNCRONO: sem esperar worker — o span já foi exportado (ADR-002)
        assertThat(exported).hasSize(1);
        SpanData span = exported.get(0);
        assertThat(span.getName()).isEqualTo("order-consumer");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("t2l.trigger")))
                .isEqualTo("lambda_event");
        runtime.shutdown();
    }

    @Test
    void recordsErrorsAndStillFlushes() throws Exception {
        List<SpanData> exported = new ArrayList<>();
        Trace2LocalLambdaRuntime runtime = Trace2LocalLambdaRuntime.create(
                Trace2LocalConfig.defaults(), new InMemoryExporter(exported));

        try {
            runtime.around(mock(Context.class), () -> {
                throw new IllegalStateException("boom");
            });
            assertThat(true).as("deveria lançar").isFalse();
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).isEqualTo("boom");
        }

        assertThat(exported).hasSize(1);
        assertThat(exported.get(0).getStatus().getStatusCode().name()).isEqualTo("ERROR");
        runtime.shutdown();
    }

    @Test
    void handlerBaseClassWrapsDelegation() throws Exception {
        List<SpanData> exported = new ArrayList<>();
        Trace2LocalLambdaRuntime runtime = Trace2LocalLambdaRuntime.create(
                Trace2LocalConfig.defaults(), new InMemoryExporter(exported));
        var handler = new Trace2LocalLambdaHandler<String, String>(runtime) {
            @Override
            protected String handle(String input, Context context) {
                return "echo:" + input;
            }
        };

        Context ctx = mock(Context.class);
        when(ctx.getFunctionName()).thenReturn("echo-handler");
        String result = handler.handleRequest("olá", ctx);

        assertThat(result).isEqualTo("echo:olá");
        assertThat(exported).hasSize(1);
        assertThat(exported.get(0).getName()).isEqualTo("echo-handler");
        runtime.shutdown();
    }

    @Test
    void stationTokenIsSentAsBearerOnOtlpExport() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/v1/traces", exchange -> {
            captured.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] ok = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (var out = exchange.getResponseBody()) {
                out.write(ok);
            }
        });
        server.start();
        try {
            Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                    .stationEndpoint("http://127.0.0.1:" + server.getAddress().getPort())
                    .stationToken("sekrit-lambda")
                    .build();
            Trace2LocalLambdaRuntime runtime = Trace2LocalLambdaRuntime.forStation(cfg);
            Context ctx = mock(Context.class);
            when(ctx.getFunctionName()).thenReturn("order-consumer");
            when(ctx.getAwsRequestId()).thenReturn("req-token-1");

            runtime.around(ctx, () -> "done");

            // o exportador OTLP envia o Bearer automaticamente (ADR-007/§8.1)
            assertThat(captured.get()).isEqualTo("Bearer sekrit-lambda");
            runtime.shutdown();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configFromEnvReadsStationToken() {
        // env não é mutável em teste: valida o fallback de propriedade de sistema
        System.setProperty("trace2local.station.endpoint", "http://127.0.0.1:19876");
        System.setProperty("trace2local.station.token", "prop-token");
        try {
            Trace2LocalConfig cfg = Trace2LocalLambda.configFromEnv();
            assertThat(cfg.stationEndpoint()).isEqualTo("http://127.0.0.1:19876");
            assertThat(cfg.stationToken()).isEqualTo("prop-token");
        } finally {
            System.clearProperty("trace2local.station.endpoint");
            System.clearProperty("trace2local.station.token");
        }
    }

    private static final class InMemoryExporter implements SpanExporter {
        private final List<SpanData> target;

        InMemoryExporter(List<SpanData> target) {
            this.target = target;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            target.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
