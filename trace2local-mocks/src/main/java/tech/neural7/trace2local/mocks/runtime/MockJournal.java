package tech.neural7.trace2local.mocks.runtime;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Diário de requisições atendidas pelo mock (o "request journal" do WireMock):
 * casou ou não, com qual stub e variação, e o {@code traceId} — que liga a chamada
 * simulada à execução na árvore. Limitado (FIFO); nunca guarda corpo nem cabeçalhos.
 */
public final class MockJournal {

    /** Uma entrada. {@code stubId == null} = não casou. */
    public record Entry(Instant at, String binding, String method, String path, int status, String stubId,
                        List<String> applied, String outcome, String traceId, long tookMs) {}

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long total;

    public MockJournal(int capacity) {
        this.capacity = Math.max(10, capacity);
    }

    public synchronized void record(Entry e) {
        if (entries.size() >= capacity) {
            entries.removeFirst();
        }
        entries.addLast(e);
        total++;
    }

    /** Mais recentes primeiro; {@code binding == null} = todos. */
    public synchronized List<Entry> list(String binding, int limit) {
        List<Entry> out = new ArrayList<>();
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            Entry e = it.next();
            if (binding == null || binding.equals(e.binding())) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized long count(String binding) {
        return binding == null ? total : entries.stream().filter(e -> binding.equals(e.binding())).count();
    }

    public synchronized void clear(String binding) {
        if (binding == null) {
            entries.clear();
        } else {
            entries.removeIf(e -> binding.equals(e.binding()));
        }
    }

    /** {@code traceId} a partir do {@code traceparent} (W3C) — vazio se ausente/ilegível. */
    public static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] p = traceparent.trim().split("-");
        return p.length >= 4 && p[1].length() == 32 ? p[1] : null;
    }
}
