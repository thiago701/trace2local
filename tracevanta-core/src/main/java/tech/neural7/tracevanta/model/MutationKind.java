package tech.neural7.tracevanta.model;

/** Tipo de mutação de dados observada (SPEC §4.6). */
public enum MutationKind {
    CREATE,
    UPDATE,
    DELETE,
    READ_ONLY
}
