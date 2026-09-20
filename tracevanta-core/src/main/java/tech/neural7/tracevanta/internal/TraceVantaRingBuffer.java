package tech.neural7.tracevanta.internal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fila limitada com descarte na borda (ADR-006): {@code offer()}, nunca {@code put()}.
 * Fila cheia ⇒ evento descartado e contador {@code tracevanta.dropped} incrementado —
 * a perda é declarada, nunca escondida.
 */
public final class TraceVantaRingBuffer implements AutoCloseable {

    private final ArrayBlockingQueue<TraceVantaEvent> queue;
    private final LongAdder dropped = new LongAdder();

    public TraceVantaRingBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    /** Nunca bloqueia; devolve {@code false} quando o evento foi descartado. */
    public boolean offer(TraceVantaEvent event) {
        boolean accepted = queue.offer(event);
        if (!accepted) {
            dropped.increment();
        }
        return accepted;
    }

    public TraceVantaEvent poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public long dropped() {
        return dropped.sum();
    }

    public int size() {
        return queue.size();
    }

    @Override
    public void close() {
        queue.clear();
    }
}
