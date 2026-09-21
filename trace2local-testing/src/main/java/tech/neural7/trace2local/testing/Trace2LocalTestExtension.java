package tech.neural7.trace2local.testing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.otel.Trace2LocalSpanProcessor;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;

/**
 * Implementação do {@link Trace2LocalTest}: pipeline em memória + SDK OTel com o
 * {@link Trace2LocalSpanProcessor}. O GlobalOpenTelemetry é (re)armado em cada
 * método de teste — DEPOIS de todos os {@code beforeAll} — para o SDK do teste
 * valer independentemente da ordem das extensões.
 */
public class Trace2LocalTestExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    private OpenTelemetrySdk sdk;

    @Override
    public void beforeAll(ExtensionContext context) {
        Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                .quiescenceMs(1500)
                .retentionMaxExecutions(200)
                .build();
        Trace2LocalRuntime.start(cfg);
        Trace2LocalPipeline pipeline = Trace2LocalRuntime.pipeline();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new Trace2LocalSpanProcessor(pipeline.buffer()::offer, cfg))
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
        // os instrumentos leem Trace2LocalOtel.get(): em teste, o SDK ativo é o da extensão
        tech.neural7.trace2local.otel.Trace2LocalOtel.register(sdk);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        GlobalOpenTelemetry.resetForTest();
        tech.neural7.trace2local.otel.Trace2LocalOtel.register(null);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (sdk != null) {
            sdk.getSdkTracerProvider().close();
        }
        GlobalOpenTelemetry.resetForTest();
        Trace2LocalRuntime.stop();
    }
}
