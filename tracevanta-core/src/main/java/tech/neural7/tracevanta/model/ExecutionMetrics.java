package tech.neural7.tracevanta.model;

/**
 * Métricas de uma execução (SPEC §4.6) — incluem as perdas, para que a UI as mostre.
 */
public record ExecutionMetrics(int nodeCount, int maxDepth, int lostSpans, long droppedEvents) {

    public static final ExecutionMetrics EMPTY = new ExecutionMetrics(0, 0, 0, 0);
}
