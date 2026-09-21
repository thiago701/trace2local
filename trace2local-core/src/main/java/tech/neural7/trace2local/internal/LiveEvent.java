package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.Trigger;
import tech.neural7.trace2local.model.Warning;

import java.time.Instant;

/**
 * Eventos vivos emitidos pelo assembler para o hub SSE (SPEC §5.2).
 * Os nomes mapeiam 1:1 para os tipos do stream.
 */
public sealed interface LiveEvent
        permits LiveEvent.ExecutionStarted, LiveEvent.NodeUpserted, LiveEvent.NodeMutation,
        LiveEvent.ExecutionCompleted, LiveEvent.SystemWarning {

    record ExecutionStarted(String executionId, String traceId, Trigger trigger, Instant at)
            implements LiveEvent {}

    record NodeUpserted(String executionId, Node node) implements LiveEvent {}

    record NodeMutation(String executionId, String nodeId, DataMutation mutation) implements LiveEvent {}

    record ExecutionCompleted(String executionId, Execution execution) implements LiveEvent {}

    record SystemWarning(Warning warning) implements LiveEvent {}
}
