package tech.neural7.trace2local.internal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fila limitada com descarte na borda (ADR-006): {@code offer()}, nunca {@code put()}.
 * Fila cheia ⇒ evento descartado e contador {@code trace2local.dropped} incrementado —
 * a perda é declarada, nunca escondida.
 */
public final class Trace2LocalRingBuffer implements AutoCloseable {

    private final ArrayBlockingQueue<Trace2LocalEvent> queue;
    private final LongAdder dropped = new LongAdder();

    public Trace2LocalRingBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    /** Nunca bloqueia; devolve {@code false} quando o evento foi descartado. */
    public boolean offer(Trace2LocalEvent event) {
        boolean accepted = queue.offer(event);
        if (!accepted) {
            dropped.increment();
        }
        return accepted;
    }

    public Trace2LocalEvent poll(long timeoutMs) throws InterruptedException {
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
