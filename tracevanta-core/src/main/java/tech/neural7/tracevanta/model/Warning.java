package tech.neural7.tracevanta.model;

import java.time.Instant;

/**
 * Aviso de honestidade: o que o TraceVanta não conseguiu ver (SPEC §6.2 — "a UI
 * NÃO DEVE esconder o que o TraceVanta não viu").
 */
public record Warning(WarningKind kind, int count, String message, Instant at) {

    public enum WarningKind {
        /** Eventos descartados na borda do ring buffer (ADR-006). */
        EVENTS_DROPPED,
        /** Nós cujo pai nunca chegou — reparentados na raiz com status ORPHANED (I1). */
        ORPHANED_NODES,
        /** Contexto possivelmente perdido em thread não instrumentada (SPEC §4.11). */
        CONTEXT_LOST,
        /** Spans iniciados sem fim dentro da janela de quiescência. */
        LOST_SPANS,
        /** Delta de dados indisponível para a operação. */
        DELTA_UNAVAILABLE
    }
}
