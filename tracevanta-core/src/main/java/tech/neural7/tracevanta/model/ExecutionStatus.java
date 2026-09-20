package tech.neural7.tracevanta.model;

/** Estado de uma execução completa (SPEC §4.6). */
public enum ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    /** Árvore incompleta por perda declarada (eventos descartados, spans perdidos, nós órfãos). */
    PARTIAL,
    /** A raiz nunca chegou: todos os nós ficaram órfãos. */
    ORPHANED
}
