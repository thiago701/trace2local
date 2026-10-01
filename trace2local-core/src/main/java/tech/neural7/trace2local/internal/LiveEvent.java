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
        LiveEvent.ExecutionCompleted, LiveEvent.ExecutionMerged, LiveEvent.ExecutionRenamed,
        LiveEvent.SystemWarning {

    record ExecutionStarted(String executionId, String traceId, Trigger trigger, Instant at)
            implements LiveEvent {}

    record NodeUpserted(String executionId, Node node) implements LiveEvent {}

    record NodeMutation(String executionId, String nodeId, DataMutation mutation) implements LiveEvent {}

    record ExecutionCompleted(String executionId, Execution execution) implements LiveEvent {}

    /**
     * Continuação tardia (SPEC §4.11): a execução {@code executionId} (consumidor
     * assíncrono que chegou depois da quiescência do produtor) foi FUNDIDA na árvore
     * de {@code intoExecutionId}. A UI descarta a primeira e recarrega a segunda.
     */
    record ExecutionMerged(String executionId, String intoExecutionId, String traceId) implements LiveEvent {}

    /**
     * A execução nasceu com id provisório (os spans filhos chegaram antes da raiz — ordem
     * natural do export OTLP em lote) e ganhou o id declarado pela raiz
     * ({@code t2l.execution.id}). A UI troca a chave sem perder seleção nem nós.
     */
    record ExecutionRenamed(String executionId, String toExecutionId, String traceId) implements LiveEvent {}

    record SystemWarning(Warning warning) implements LiveEvent {}
}
