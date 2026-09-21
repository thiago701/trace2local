package tech.neural7.trace2local.model;

/** Tipo de mutação de dados observada (SPEC §4.6). */
public enum MutationKind {
    CREATE,
    UPDATE,
    DELETE,
    READ_ONLY
}
