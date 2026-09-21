package tech.neural7.trace2local.spi;

import tech.neural7.trace2local.internal.Trace2LocalEvent;
import tech.neural7.trace2local.model.DataMutation;

import java.time.Instant;

/**
 * Evento do Data Mutation Channel (ADR-003 / SPEC §4.10): delta de dados
 * correlacionado por {@code spanId}, fundido pelo assembler no TVEM.
 */
public record MutationEvent(
        String spanId,
        String traceId,
        DataMutation mutation,
        Instant at) implements Trace2LocalEvent {}
