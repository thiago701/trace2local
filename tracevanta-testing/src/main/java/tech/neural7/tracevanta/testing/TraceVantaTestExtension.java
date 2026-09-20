package tech.neural7.tracevanta.testing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.otel.TraceVantaSpanProcessor;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;

/**
 * Implementação do {@link TraceVantaTest}: pipeline em memória + SDK OTel com o
 * {@link TraceVantaSpanProcessor}. O GlobalOpenTelemetry é (re)armado em cada
 * método de teste — DEPOIS de todos os {@code beforeAll} — para o SDK do teste
 * valer independentemente da ordem das extensões.
 */
public class TraceVantaTestExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    private OpenTelemetrySdk sdk;

    @Override
    public void beforeAll(ExtensionContext context) {
        TraceVantaConfig cfg = TraceVantaConfig.builder()
                .quiescenceMs(1500)
                .retentionMaxExecutions(200)
                .build();
        TraceVantaRuntime.start(cfg);
        TraceVantaPipeline pipeline = TraceVantaRuntime.pipeline();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new TraceVantaSpanProcessor(pipeline.buffer()::offer, cfg))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(io.opentelemetry.context.propagation.ContextPropagators.create(
                        io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(sdk);
        // os instrumentos leem TraceVantaOtel.get(): em teste, o SDK ativo é o da extensão
        tech.neural7.tracevanta.otel.TraceVantaOtel.register(sdk);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        GlobalOpenTelemetry.resetForTest();
        tech.neural7.tracevanta.otel.TraceVantaOtel.register(null);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (sdk != null) {
            sdk.getSdkTracerProvider().close();
        }
        GlobalOpenTelemetry.resetForTest();
        TraceVantaRuntime.stop();
    }
}
