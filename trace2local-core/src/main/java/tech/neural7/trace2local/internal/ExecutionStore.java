package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionSummary;

import java.util.List;
import java.util.Optional;

/**
 * Leitura do acervo de execuções em memória (SPEC §7.3 — tudo em memória, LRU).
 * Implementado pelo {@link TraceAssembler}.
 */
public interface ExecutionStore {

    List<ExecutionSummary> recent(int limit);

    Optional<Execution> get(String executionId);

    void clear();

    long droppedEvents();

    /** Erros internos do assembler (degradação preferida à falha — contados, não escondidos). */
    long internalErrors();

    int bufferSize();

    int liveExecutions();
}
