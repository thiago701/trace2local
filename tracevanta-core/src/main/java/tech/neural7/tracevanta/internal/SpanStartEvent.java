package tech.neural7.tracevanta.internal;

import tech.neural7.tracevanta.model.NodeKind;
import tech.neural7.tracevanta.model.Trigger;

import java.time.Instant;
import java.util.Map;

/** Span aberto: cria o nó em estado PENDING na árvore ao vivo. */
public record SpanStartEvent(
        String traceId,
        String spanId,
        String parentSpanId,
        String executionId,
        Trigger trigger,
        NodeKind kind,
        String label,
        Map<String, String> attributes,
        Instant at) implements TraceVantaEvent {}
