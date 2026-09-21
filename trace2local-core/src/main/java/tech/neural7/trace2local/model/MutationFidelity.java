package tech.neural7.trace2local.model;

/**
 * Declaração de origem do dado (invariante I3 — SPEC §4.6):
 * a UI nunca exibe dado inferido com aparência de dado observado.
 */
public enum MutationFidelity {
    /** Observado diretamente na resposta da operação. */
    EXACT,
    /** Derivado (ex.: intenção de um SQL por parse). */
    INFERRED,
    /** A origem não forneceu o dado (ex.: TransactWriteItems na v0.1). */
    UNAVAILABLE
}
