package tech.neural7.tracevanta.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Uma jornada completa da execução (SPEC §4.6). Modelo público do produto — não é
 * o modelo do OpenTelemetry.
 */
public record Execution(
        String executionId,
        String traceId,
        ExecutionStatus status,
        Trigger trigger,
        Instant startedAt,
        Duration duration,
        List<Node> roots,
        ExecutionMetrics metrics,
        List<Warning> warnings) {}
