package tech.neural7.tracevanta.internal;

import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.model.NodeKind;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RingBufferTest {

    @Test
    void dropsAtTheEdgeAndCountsHonestly() {
        TraceVantaRingBuffer buffer = new TraceVantaRingBuffer(2);
        assertThat(buffer.offer(event("t1", "a"))).isTrue();
        assertThat(buffer.offer(event("t1", "b"))).isTrue();
        assertThat(buffer.offer(event("t1", "c"))).isFalse(); // cheio ⇒ descarte
        assertThat(buffer.dropped()).isEqualTo(1);
        assertThat(buffer.size()).isEqualTo(2);
    }

    @Test
    void neverBlocksOnOffer() {
        TraceVantaRingBuffer buffer = new TraceVantaRingBuffer(1);
        buffer.offer(event("t1", "a"));
        long start = System.nanoTime();
        buffer.offer(event("t1", "b")); // cheio — não pode bloquear (ADR-006)
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(100);
        assertThat(buffer.dropped()).isEqualTo(1);
    }

    private static SpanStartEvent event(String traceId, String spanId) {
        return new SpanStartEvent(traceId, spanId, null, null, null,
                NodeKind.UNKNOWN, "n", java.util.Map.of(), Instant.now());
    }
}
