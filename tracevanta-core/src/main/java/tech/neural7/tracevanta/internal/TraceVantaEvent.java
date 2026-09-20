package tech.neural7.tracevanta.internal;

import tech.neural7.tracevanta.spi.MutationEvent;

import java.time.Instant;

/**
 * Evento imutável que trafega no pipeline (ring buffer → assembler).
 * Única alocação permitida no caminho de ingest (ADR-006 / NFR-2).
 */
public interface TraceVantaEvent {

    String traceId();

    Instant at();
}
