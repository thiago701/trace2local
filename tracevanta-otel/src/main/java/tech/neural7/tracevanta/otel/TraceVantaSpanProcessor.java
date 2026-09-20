package tech.neural7.tracevanta.otel;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.SpanEndEvent;
import tech.neural7.tracevanta.internal.SpanStartEvent;
import tech.neural7.tracevanta.internal.TraceVantaEvent;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Ponte OTel → TVEM (ADR-001): um {@link SpanProcessor} acrescentado ao pipeline
 * do desenvolvedor — nunca o substitui. O contrato do OTel é explícito:
 * {@code onStart}/{@code onEnd} são chamados na thread de execução e NÃO DEVEM
 * bloquear nem lançar. O único trabalho permitido aqui é montar o evento imutável
 * e oferecê-lo ao sink (ADR-006 / NFR-2). Tudo é encapsulado em try/catch —
 * uma ferramenta de debug que derruba a app é pior que nenhuma (SPEC §7.3).
 */
public final class TraceVantaSpanProcessor implements SpanProcessor {

    private final Consumer<TraceVantaEvent> sink;
    private final SemanticMapper mapper;
    private final TraceVantaConfig cfg;

    public TraceVantaSpanProcessor(Consumer<TraceVantaEvent> sink, TraceVantaConfig cfg) {
        this(sink, cfg, new DefaultSemanticMapper());
    }

    public TraceVantaSpanProcessor(Consumer<TraceVantaEvent> sink, TraceVantaConfig cfg, SemanticMapper mapper) {
        this.sink = sink;
        this.cfg = cfg;
        this.mapper = mapper;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        try {
            var data = span.toSpanData();
            var mapped = SpanDataSupport.map(data, mapper, cfg);
            SpanStartEvent event = new SpanStartEvent(
                    data.getTraceId(),
                    data.getSpanId(),
                    data.getParentSpanContext().isValid() ? data.getParentSpanId() : null,
                    mapped.executionId(),
                    mapped.trigger(),
                    mapped.kind(),
                    mapped.label(),
                    mapped.attributes(),
                    epoch(data.getStartEpochNanos()));
            sink.accept(event);
        } catch (Throwable ignored) {
            // nunca lança, nunca bloqueia
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        try {
            var data = span.toSpanData();
            var mapped = SpanDataSupport.map(data, mapper, cfg);
            // payload via atributo interno (definido pelo filtro HTTP do starter):
            // sai do mapa de atributos e vira Payload do nó
            java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>(mapped.attributes());
            String payloadRequest = attributes.remove("tv.payload.request");
            String payloadResponse = attributes.remove("tv.payload.response");
            SpanEndEvent event = new SpanEndEvent(
                    data.getTraceId(),
                    data.getSpanId(),
                    data.getParentSpanContext().isValid() ? data.getParentSpanId() : null,
                    mapped.executionId(),
                    mapped.trigger(),
                    mapped.kind(),
                    mapped.label(),
                    attributes,
                    epoch(data.getStartEpochNanos()),
                    epoch(data.getEndEpochNanos()),
                    mapped.error() != null,
                    mapped.error(),
                    payloadRequest,
                    payloadResponse,
                    mapped.linkedSpanIds(),
                    false);
            sink.accept(event);
        } catch (Throwable ignored) {
            // nunca lança, nunca bloqueia
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
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
    public void close() {
        // nada a liberar
    }

    private static Instant epoch(long epochNanos) {
        return Instant.ofEpochSecond(epochNanos / 1_000_000_000L, epochNanos % 1_000_000_000L);
    }
}
