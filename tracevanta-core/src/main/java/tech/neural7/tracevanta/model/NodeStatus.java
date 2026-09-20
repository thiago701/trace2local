package tech.neural7.tracevanta.model;

/** Estado de um nó da árvore. */
public enum NodeStatus {
    /** Operação concluída sem erro. */
    OK,
    /** Operação concluída com erro. */
    ERROR,
    /** Ainda em andamento. */
    PENDING,
    /**
     * Sub-árvore cujo pai não chegou (reparentada na raiz — invariante I1) ou
     * produtor aguardando consumo (SPEC §1.5, jornada JC-3).
     */
    ORPHANED
}
