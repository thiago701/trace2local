package tech.neural7.tracevanta.internal;

import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.spi.DataMutationChannel;

import java.util.function.Consumer;

/**
 * Fachada do pipeline em memória: ring buffer → assembler → ouvintes (hub SSE).
 * Um por processo no modo Embedded e um no Station (modo Companion).
 */
public final class TraceVantaPipeline implements AutoCloseable {

    private final TraceVantaRingBuffer buffer;
    private final TraceAssembler assembler;

    private TraceVantaPipeline(TraceVantaConfig cfg) {
        this.buffer = new TraceVantaRingBuffer(cfg.bufferCapacity());
        this.assembler = new TraceAssembler(buffer, cfg);
    }

    /** Cria sem iniciar — útil para testes alimentarem eventos de forma determinística. */
    public static TraceVantaPipeline create(TraceVantaConfig cfg) {
        return new TraceVantaPipeline(cfg);
    }

    public static TraceVantaPipeline start(TraceVantaConfig cfg) {
        TraceVantaPipeline pipeline = new TraceVantaPipeline(cfg);
        pipeline.start();
        return pipeline;
    }

    public void start() {
        assembler.start();
        DataMutationChannel.setSink(event -> buffer.offer(event));
    }

    public void addListener(Consumer<LiveEvent> listener) {
        assembler.addListener(listener);
    }

    public TraceVantaRingBuffer buffer() {
        return buffer;
    }

    public ExecutionStore store() {
        return assembler;
    }

    public TraceAssembler assembler() {
        return assembler;
    }

    @Override
    public void close() {
        DataMutationChannel.setSink(null);
        assembler.close();
        buffer.close();
    }
}
