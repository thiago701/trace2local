package tech.neural7.tracevanta.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Resumo de execução para o painel "Recent Executions" (GET /api/executions — SPEC §5.1).
 */
public record ExecutionSummary(
        String executionId,
        String traceId,
        ExecutionStatus status,
        Trigger trigger,
        Instant startedAt,
        Duration duration,
        String rootLabel,
        int nodeCount) {}
