package tech.neural7.trace2local.config;

/** Níveis de captura de mutação SQL (SPEC §4.10, propriedade {@code trace2local.jdbc.mutation-capture}). */
public enum JdbcMutationCapture {
    /** Padrão: sem delta; apenas {@code db.query.text} redigido e updateCount. */
    OFF,
    /** Intenção derivada por parse leve do comando, com {@code fidelity=INFERRED}. */
    INFERRED,
    /** Opt-in explícito com aviso: SELECT prévio na mesma conexão e transação (v0.2 — não implementado ainda). */
    BEFORE_IMAGE
}
