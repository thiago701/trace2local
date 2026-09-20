package tech.neural7.tracevanta.internal;

import tech.neural7.tracevanta.model.DataMutation;
import tech.neural7.tracevanta.model.Execution;
import tech.neural7.tracevanta.model.Node;
import tech.neural7.tracevanta.model.Trigger;
import tech.neural7.tracevanta.model.Warning;

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
