package tech.neural7.tracevanta.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.TraceVantaEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Helper de boot do SDK OTel com o processor do TraceVanta ACrescentado ao
 * pipeline (ADR-001) — se o dev já tem exporters configurados, eles continuam;
 * o TraceVanta apenas se soma.
 *
 * <p><b>Por que um registro próprio e não o GlobalOpenTelemetry?</b> A API do
 * OTel trava o global no noop na PRIMEIRA chamada a {@code get()} (o
 * {@code getOrNoop} grava o noop no campo — ver bytecode da API 1.66) e todo
 * {@code set()} posterior lança. Um app Spring tem inúmeros {@code get()} antes
 * de qualquer bean; portanto os instrumentos do TraceVanta leem de
 * {@link #get()}: o SDK registrado quando é o nosso, ou o global do dev quando
 * ele trouxe o próprio OTel — caso em que os spans dele chegam à árvore pelo
 * {@link TraceVantaOtelConfigurer} (ServiceLoader).
 */
public final class TraceVantaOtel {

    private static volatile OpenTelemetry own;

    private TraceVantaOtel() {}

    /** Registra o SDK do TraceVanta no boot do starter. */
    public static void register(OpenTelemetry sdk) {
        own = sdk;
    }

    /** O SDK do TraceVanta quando ele é o dono do pipeline; senão, o global do dev. */
    public static OpenTelemetry get() {
        OpenTelemetry registered = own;
        return registered != null ? registered : GlobalOpenTelemetry.get();
    }

    public static SdkTracerProviderBuilder tracerProviderBuilder(
            TraceVantaConfig cfg, Consumer<TraceVantaEvent> sink, List<SpanExporter> exporters) {
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .addSpanProcessor(new TraceVantaSpanProcessor(sink, cfg));
        for (SpanExporter exporter : exporters) {
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }
        return builder;
    }

    /** Constrói, registra no {@link #register} e tenta o global (best-effort). */
    public static OpenTelemetrySdk buildAndRegister(
            TraceVantaConfig cfg, Consumer<TraceVantaEvent> sink, List<SpanExporter> exporters) {
        SdkTracerProvider provider = tracerProviderBuilder(cfg, sink, exporters).build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(
                        io.opentelemetry.context.propagation.TextMapPropagator.composite(
                                io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance(),
                                io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator.getInstance())))
                .build();
        register(sdk);
        try {
            // best-effort: se nenhum get() trancou o global ainda, ele também passa a
            // ser o nosso; se trancou (caso comum em Spring), os instrumentos usam o
            // registro próprio acima e nada se perde.
            if (GlobalOpenTelemetry.get().getTracerProvider()
                    == io.opentelemetry.api.trace.TracerProvider.noop()) {
                GlobalOpenTelemetry.set(sdk);
            }
        } catch (IllegalStateException alreadySet) {
            // esperado: o noop já foi trancado por um get() anterior — registro próprio cobre
        }
        return sdk;
    }

    public static void shutdown(OpenTelemetrySdk sdk) {
        try {
            sdk.getSdkTracerProvider().shutdown();
        } catch (Throwable ignored) {
            // shutdown é best-effort
        }
    }

    public static SpanProcessor processor(Consumer<TraceVantaEvent> sink, TraceVantaConfig cfg) {
        return new TraceVantaSpanProcessor(sink, cfg);
    }
}
