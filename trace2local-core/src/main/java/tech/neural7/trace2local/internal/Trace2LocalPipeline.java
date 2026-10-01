package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.spi.DataMutationChannel;

import java.util.function.Consumer;

/**
 * Fachada do pipeline em memória: ring buffer → assembler → ouvintes (hub SSE).
 * Um por processo no modo Embedded e um no Station (modo Companion).
 */
public final class Trace2LocalPipeline implements AutoCloseable {

    private final Trace2LocalRingBuffer buffer;
    private final TraceAssembler assembler;
    /** Logs correlacionados (linha do tempo estilo CloudWatch). */
    private final LogStore logs = new LogStore();

    private Trace2LocalPipeline(Trace2LocalConfig cfg) {
        this.buffer = new Trace2LocalRingBuffer(cfg.bufferCapacity());
        this.assembler = new TraceAssembler(buffer, cfg);
    }

    /** Cria sem iniciar — útil para testes alimentarem eventos de forma determinística. */
    public static Trace2LocalPipeline create(Trace2LocalConfig cfg) {
        return new Trace2LocalPipeline(cfg);
    }

    public static Trace2LocalPipeline start(Trace2LocalConfig cfg) {
        Trace2LocalPipeline pipeline = new Trace2LocalPipeline(cfg);
        pipeline.start();
        return pipeline;
    }

    public void start() {
        assembler.start();
        DataMutationChannel.setSink(event -> buffer.offer(event));
        tech.neural7.trace2local.spi.LogChannel.setSink(logs::append);
    }

    public void addListener(Consumer<LiveEvent> listener) {
        assembler.addListener(listener);
    }

    public Trace2LocalRingBuffer buffer() {
        return buffer;
    }

    public ExecutionStore store() {
        return assembler;
    }

    /** Acervo de logs correlacionados por trace/RequestId. */
    public LogStore logs() {
        return logs;
    }

    public TraceAssembler assembler() {
        return assembler;
    }

    @Override
    public void close() {
        DataMutationChannel.setSink(null);
        tech.neural7.trace2local.spi.LogChannel.setSink(null);
        assembler.close();
        buffer.close();
    }
}
