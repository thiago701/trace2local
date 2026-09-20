package tech.neural7.tracevanta.internal;

import tech.neural7.tracevanta.model.ErrorInfo;
import tech.neural7.tracevanta.model.NodeKind;
import tech.neural7.tracevanta.model.Trigger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Span fechado: congela o nó com semântica, atributos, erro e payload já redigidos. */
public record SpanEndEvent(
        String traceId,
        String spanId,
        String parentSpanId,
        String executionId,
        Trigger trigger,
        NodeKind kind,
        String label,
        Map<String, String> attributes,
        Instant startedAt,
        Instant at,
        boolean error,
        ErrorInfo errorInfo,
        String payloadRequest,
        String payloadResponse,
        List<String> linkedSpanIds,
        // true no ingest OTLP do Station: o protocolo não carrega evento de início,
        // então a ausência do start é POR DESENHO — não gera aviso EVENTS_DROPPED (SPEC §5.3)
        boolean startDeliberatelyAbsent) implements TraceVantaEvent {}
