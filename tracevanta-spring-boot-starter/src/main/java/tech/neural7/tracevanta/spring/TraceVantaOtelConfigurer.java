package tech.neural7.tracevanta.spring;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import tech.neural7.tracevanta.internal.TraceVantaEvent;
import tech.neural7.tracevanta.otel.TraceVantaSpanProcessor;

import java.util.function.Consumer;

/**
 * Anexa o processor do TraceVanta ao SDK do OpenTelemetry quando a app usa o
 * autoconfigure oficial do OTel (caminho sem agente — ADR-001). Carregado por
 * {@code ServiceLoader} (AOT-safe); o sink é resolvido no momento do evento via
 * {@link TraceVantaBridge}, então a ordem de inicialização não importa.
 */
public final class TraceVantaOtelConfigurer implements SdkTracerProviderConfigurer {

    @Override
    public void configure(SdkTracerProviderBuilder builder, ConfigProperties configProperties) {
        builder.addSpanProcessor(new BridgedProcessor());
    }

    /** Processor cujo sink é lido no primeiro evento — robusto a ordem de boot, sem alocação por evento. */
    static final class BridgedProcessor implements SpanProcessor {

        private volatile SpanProcessor cached;

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            delegate().onStart(parentContext, span);
        }

        @Override
        public boolean isStartRequired() {
            return delegate().isStartRequired();
        }

        @Override
        public void onEnd(ReadableSpan span) {
            delegate().onEnd(span);
        }

        @Override
        public boolean isEndRequired() {
            return delegate().isEndRequired();
        }

        private SpanProcessor delegate() {
            SpanProcessor current = cached;
            if (current != null && current != NOOP) {
                return current;
            }
            Consumer<TraceVantaEvent> sink = TraceVantaBridge.sink();
            if (sink == null) {
                return NOOP; // sem cache permanente: o sink pode subir depois do primeiro span
            }
            current = new TraceVantaSpanProcessor(sink, TraceVantaBridge.config());
            cached = current;
            return current;
        }

        private static final SpanProcessor NOOP = new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {}

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {}

            @Override
            public boolean isEndRequired() {
                return false;
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode forceFlush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public void close() {}
        };
    }
}
