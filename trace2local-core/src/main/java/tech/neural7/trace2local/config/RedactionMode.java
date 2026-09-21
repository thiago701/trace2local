package tech.neural7.trace2local.config;

/** Modo de redaction (SPEC §8.3, propriedade {@code trace2local.redaction.mode}). */
public enum RedactionMode {
    /** Por chave E por padrão de valor. Padrão. */
    STRICT,
    /** Apenas por chave sensível. */
    KEYS,
    /** Desligada (apenas truncamento por tamanho). */
    OFF
}
