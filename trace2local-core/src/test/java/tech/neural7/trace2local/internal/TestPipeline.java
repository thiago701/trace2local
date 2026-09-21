package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.model.Execution;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Ajudante de teste: pipeline em memória + espera por completude. */
public final class TestPipeline implements AutoCloseable {

    private final Trace2LocalPipeline pipeline;
    private final List<LiveEvent> events = new CopyOnWriteArrayList<>();

    public TestPipeline(Trace2LocalConfig cfg) {
        this.pipeline = Trace2LocalPipeline.create(cfg);
        this.pipeline.addListener(events::add);
    }

    /** Liga o assembler — chamar DEPOIS de oferecer os eventos, para teste determinístico. */
    public void start() {
        pipeline.start();
    }

    public Trace2LocalPipeline pipeline() {
        return pipeline;
    }

    public List<LiveEvent> events() {
        return events;
    }

    public Execution awaitExecution(String executionId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Execution> execution = pipeline.store().get(executionId);
            if (execution.isPresent()) {
                return execution.get();
            }
            sleep(20);
        }
        throw new AssertionError("Execução " + executionId + " não completou em " + timeout);
    }

    /** Localiza a execução pelo traceId — independente do TV-NNNNN gerado. */
    public Execution awaitExecutionByTrace(String traceId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (var summary : pipeline.store().recent(200)) {
                if (summary.traceId().equals(traceId)) {
                    Optional<Execution> execution = pipeline.store().get(summary.executionId());
                    if (execution.isPresent()) {
                        return execution.get();
                    }
                }
            }
            sleep(20);
        }
        throw new AssertionError("Execução do trace " + traceId + " não completou em " + timeout);
    }

    public LiveEvent.ExecutionCompleted awaitCompleted(String executionId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (LiveEvent event : events) {
                if (event instanceof LiveEvent.ExecutionCompleted c && c.executionId().equals(executionId)) {
                    return c;
                }
            }
            sleep(20);
        }
        throw new AssertionError("ExecutionCompleted " + executionId + " não chegou em " + timeout);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        pipeline.close();
    }
}
