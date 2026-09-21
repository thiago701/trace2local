package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.spi.MutationEvent;

import java.time.Instant;

/**
 * Evento imutável que trafega no pipeline (ring buffer → assembler).
 * Única alocação permitida no caminho de ingest (ADR-006 / NFR-2).
 */
public interface Trace2LocalEvent {

    String traceId();

    Instant at();
}
