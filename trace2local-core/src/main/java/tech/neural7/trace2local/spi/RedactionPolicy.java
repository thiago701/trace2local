package tech.neural7.trace2local.spi;

import tech.neural7.trace2local.config.RedactionMode;

/** Política de redaction declarada por uma extensão (SPEC §4.7). */
public enum RedactionPolicy {
    /** Segue a configuração global. */
    INHERIT,
    /** Redige como {@link RedactionMode#STRICT}, mesmo que o global esteja em {@code off}. */
    STRICT,
    /** Nunca redige o que esta extensão publicar. Use com consciência. */
    NEVER
}
