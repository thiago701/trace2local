package tech.neural7.trace2local.spi;

import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Vista anti-corrupção de um span para as extensões (SPEC §4.7 / ADR-008).
 * O núcleo não conhece tipos do OpenTelemetry: a ponte (`trace2local-otel`) converte
 * {@code SpanData} nesta estrutura estável antes de qualquer código da SPI rodar.
 */
public record SpanView(
        String spanId,
        String traceId,
        String parentSpanId,
        String name,
        NodeKind kind,
        String label,
        NodeStatus status,
        Instant startedAt,
        Duration totalTime,
        Map<String, String> attributes,
        boolean error) {}
