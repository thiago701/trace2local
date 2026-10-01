package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.model.LogEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Acervo de LOGS em memória, indexado no estilo CloudWatch (SPEC §7.3 — tudo em
 * memória, limitado). Dois índices porque a correlação vem de duas fontes:
 * <ul>
 *   <li>{@code traceId} — logs da aplicação emitidos com span ativo (MDC/OTel);</li>
 *   <li>{@code requestId} — linhas do CloudWatch (START/END/REPORT e o stdout da
 *       função) que só conhecem o RequestId da invocação Lambda.</li>
 * </ul>
 * A leitura une os dois índices a partir do que a execução conhece
 * ({@code traceId} + {@code faas.invocation_id} dos nós LAMBDA).
 *
 * <p>Honestidade: linhas de plataforma SINTETIZADAS pelo wrapper são suprimidas
 * quando o CloudWatch real entregou as mesmas linhas para o RequestId; linhas
 * descartadas por limite são CONTADAS ({@link #dropped()}), nunca escondidas.
 * Mensagens chegam redigidas na origem e são redigidas de novo aqui (defesa em
 * profundidade para fontes externas como o ingest HTTP do Station).
 */
public final class LogStore {

    /** Limites padrão: 400 traces/requests × 2000 linhas. */
    public static final int DEFAULT_MAX_KEYS = 400;
    public static final int DEFAULT_MAX_LINES_PER_KEY = 2000;

    private final int maxKeys;
    private final int maxLinesPerKey;
    private final Map<String, Deque<LogEntry>> byTrace;
    private final Map<String, Deque<LogEntry>> byRequest;
    private final Deque<LogEntry> uncorrelated = new ArrayDeque<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong total = new AtomicLong();

    public LogStore() {
        this(DEFAULT_MAX_KEYS, DEFAULT_MAX_LINES_PER_KEY);
    }

    public LogStore(int maxKeys, int maxLinesPerKey) {
        this.maxKeys = Math.max(1, maxKeys);
        this.maxLinesPerKey = Math.max(1, maxLinesPerKey);
        this.byTrace = lru(this.maxKeys);
        this.byRequest = lru(this.maxKeys);
    }

    private static Map<String, Deque<LogEntry>> lru(int max) {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<LogEntry>> eldest) {
                return size() > max;
            }
        };
    }

    /** Acrescenta uma linha (nunca lança — telemetria é best-effort). */
    public void append(LogEntry entry) {
        if (entry == null || entry.message() == null) {
            return;
        }
        try {
            LogEntry clean = entry.withMessage(TextRedactor.redact(entry.message()));
            total.incrementAndGet();
            synchronized (this) {
                boolean indexed = false;
                if (notBlank(clean.traceId())) {
                    push(byTrace, clean.traceId().toLowerCase(Locale.ROOT), clean);
                    indexed = true;
                }
                if (notBlank(clean.requestId())) {
                    push(byRequest, clean.requestId(), clean);
                    indexed = true;
                }
                if (!indexed) {
                    uncorrelated.addLast(clean);
                    if (uncorrelated.size() > maxLinesPerKey) {
                        uncorrelated.removeFirst();
                        dropped.incrementAndGet();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            dropped.incrementAndGet();
        }
    }

    public void appendAll(Collection<LogEntry> entries) {
        if (entries != null) {
            entries.forEach(this::append);
        }
    }

    private void push(Map<String, Deque<LogEntry>> index, String key, LogEntry entry) {
        Deque<LogEntry> lines = index.computeIfAbsent(key, k -> new ArrayDeque<>());
        lines.addLast(entry);
        if (lines.size() > maxLinesPerKey) {
            lines.removeFirst();
            dropped.incrementAndGet();
        }
    }

    /**
     * Linhas de uma execução: união do índice por trace e por RequestId,
     * deduplicada e em ordem temporal.
     *
     * @param traceId    trace da execução (pode ser {@code null})
     * @param requestIds RequestIds das invocações Lambda da execução
     */
    public List<LogEntry> forExecution(String traceId, Collection<String> requestIds) {
        List<LogEntry> raw = new ArrayList<>();
        synchronized (this) {
            if (notBlank(traceId)) {
                Deque<LogEntry> t = byTrace.get(traceId.toLowerCase(Locale.ROOT));
                if (t != null) {
                    raw.addAll(t);
                }
            }
            if (requestIds != null) {
                for (String id : requestIds) {
                    Deque<LogEntry> r = notBlank(id) ? byRequest.get(id) : null;
                    if (r != null) {
                        raw.addAll(r);
                    }
                }
            }
        }
        return dedupe(raw);
    }

    /** Linhas sem correlação (stdout fora de invocação, CloudWatch sem RequestId). */
    public synchronized List<LogEntry> uncorrelated(int limit) {
        List<LogEntry> all = new ArrayList<>(uncorrelated);
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return all.subList(from, all.size());
    }

    static List<LogEntry> dedupe(List<LogEntry> raw) {
        // RequestIds que têm linhas de plataforma REAIS (CloudWatch): as sintetizadas saem
        Set<String> realPlatform = new HashSet<>();
        for (LogEntry e : raw) {
            if (e.source() == LogEntry.LogSource.CLOUDWATCH && e.isPlatformLine() && notBlank(e.requestId())) {
                realPlatform.add(e.requestId());
            }
        }
        Map<String, LogEntry> unique = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new java.util.HashMap<>();
        Set<LogEntry> seenInstances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (LogEntry e : raw) {
            if (!seenInstances.add(e)) {
                continue; // mesma instância vinda pelos dois índices (trace + request)
            }
            if (e.source() == LogEntry.LogSource.PLATFORM && notBlank(e.requestId())
                    && realPlatform.contains(e.requestId())) {
                continue;
            }
            // a MESMA linha pode chegar pelo wrapper (com span) e pelo CloudWatch (com stream):
            // casa a n-ésima ocorrência de cada fonte — linhas repetidas legítimas sobrevivem
            String base = (e.requestId() != null ? e.requestId() : "") + "|" + normalize(e.message())
                    + "|" + (e.isPlatformLine() ? "P" : "A");
            String sourceClass = e.source() == LogEntry.LogSource.CLOUDWATCH ? "CW" : "LOCAL";
            int nth = occurrences.merge(sourceClass + "|" + base, 1, Integer::sum);
            String key = base + "|" + nth;
            LogEntry prev = unique.get(key);
            if (prev == null) {
                unique.put(key, e);
            } else if (prev.spanId() == null && e.spanId() != null) {
                unique.put(key, e);
            }
        }
        List<LogEntry> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparing(LogEntry::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static String normalize(String message) {
        return message == null ? "" : message.strip().replaceAll("\\s+", " ");
    }

    public synchronized void clear() {
        byTrace.clear();
        byRequest.clear();
        uncorrelated.clear();
    }

    public long dropped() {
        return dropped.get();
    }

    public long total() {
        return total.get();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
