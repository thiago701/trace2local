package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.Trigger;

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
        Instant at) implements Trace2LocalEvent {}
