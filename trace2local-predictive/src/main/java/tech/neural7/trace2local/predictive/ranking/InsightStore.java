package tech.neural7.trace2local.predictive.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.predictive.api.Insight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Acervo de insights com DEDUPE (fingerprint), AGRUPAMENTO (ocorrências e
 * execuções acumuladas), COOLDOWN e SUPRESSÕES por feedback do dev — o que
 * impede a funcionalidade de virar "um novo Sonar cheio de alertas".
 *
 * <p>Feedback (persistido localmente em {@code .trace2local/history/feedback.json}):
 * <ul>
 *   <li>{@code useful} — reforça o analisador (aprendizado de precisão);</li>
 *   <li>{@code dismiss} — some por 24 h, volta se a severidade PIORAR;</li>
 *   <li>{@code expected} — comportamento aceito como normal por 7 dias (ex.: novo baseline);</li>
 *   <li>{@code mute} — silenciado até ser reativado.</li>
 * </ul>
 */
public final class InsightStore {

    public static final int MAX_INSIGHTS = 400;
    public static final int MAX_EXECUTIONS_PER_INSIGHT = 20;

    public enum Action { USEFUL, DISMISS, EXPECTED, MUTE, RESET }

    private record Suppression(Action action, Instant at, Insight.Severity severity) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Insight> byFingerprint = new LinkedHashMap<>();
    private final Map<String, Suppression> suppressions = new LinkedHashMap<>();
    private final Map<String, int[]> analyzerFeedback = new LinkedHashMap<>(); // analisador → [úteis, descartados]
    private final Map<String, Double> flowCriticality = new LinkedHashMap<>();
    private final List<Consumer<List<Insight>>> listeners = new ArrayList<>();
    private final Path feedbackFile;

    public InsightStore(Path feedbackFile) {
        this.feedbackFile = feedbackFile;
        load();
    }

    public static InsightStore inMemory() {
        return new InsightStore(null);
    }

    public synchronized void addListener(Consumer<List<Insight>> listener) {
        listeners.add(listener);
    }

    /** Criticidade do fluxo (0,5..1,2) — informada pelo pipeline a partir dos fatos. */
    public synchronized void flowCriticality(String executionId, double criticality) {
        flowCriticality.put(executionId, criticality);
        if (flowCriticality.size() > 2000) {
            flowCriticality.remove(flowCriticality.keySet().iterator().next());
        }
    }

    /** Integra novos achados (dedupe + ranking) e notifica ouvintes com o que mudou. */
    public void accept(List<Insight> found) {
        if (found == null || found.isEmpty()) {
            return;
        }
        List<Insight> changed = new ArrayList<>();
        List<Consumer<List<Insight>>> snapshot;
        synchronized (this) {
            Instant now = Instant.now();
            for (Insight i : found) {
                Insight prev = byFingerprint.get(i.fingerprint());
                int occurrences = prev == null ? 1 : prev.occurrences() + (sameExecutions(prev, i) ? 0 : 1);
                Instant firstSeen = prev == null ? now : prev.firstSeen();
                LinkedHashSet<String> executions = new LinkedHashSet<>(i.executionIds());
                if (prev != null) {
                    executions.addAll(prev.executionIds());
                }
                List<String> execs = new ArrayList<>(executions);
                if (execs.size() > MAX_EXECUTIONS_PER_INSIGHT) {
                    execs = execs.subList(0, MAX_EXECUTIONS_PER_INSIGHT);
                }
                double crit = i.executionIds().stream().map(flowCriticality::get).filter(java.util.Objects::nonNull)
                        .mapToDouble(Double::doubleValue).max().orElse(0.8);
                Insight merged = i.withRanking(0, occurrences, firstSeen, now, execs);
                merged = merged.withRanking(InsightRanker.score(merged, crit, precision(i.analyzer()), now),
                        occurrences, firstSeen, now, execs);
                byFingerprint.remove(i.fingerprint()); // reinsere no fim (mais recente)
                byFingerprint.put(i.fingerprint(), merged);
                changed.add(merged);
            }
            while (byFingerprint.size() > MAX_INSIGHTS) {
                byFingerprint.remove(byFingerprint.keySet().iterator().next());
            }
            snapshot = List.copyOf(listeners);
        }
        List<Insight> visible = changed.stream().filter(this::visible).toList();
        if (!visible.isEmpty()) {
            snapshot.forEach(l -> {
                try {
                    l.accept(visible);
                } catch (RuntimeException ignored) {
                    // ouvinte com defeito não derruba o pipeline
                }
            });
        }
    }

    /**
     * Re-análise da MESMA execução (ex.: continuação tardia fundiu o consumidor na
     * árvore): achados por-execução que a nova análise não reemitiu deixam de citar
     * a execução — e somem se não restar nenhuma outra. Evita "publicação sem
     * consumidor" sobreviver depois que o consumidor apareceu.
     *
     * @return quantos insights foram retirados por completo
     */
    public synchronized int retractStale(String executionId, java.util.Set<String> analyzers, java.util.Set<String> keepFingerprints) {
        int removed = 0;
        var it = byFingerprint.entrySet().iterator();
        List<Map.Entry<String, Insight>> rewrite = new ArrayList<>();
        while (it.hasNext()) {
            var en = it.next();
            Insight i = en.getValue();
            if (!analyzers.contains(i.analyzer()) || keepFingerprints.contains(i.fingerprint())
                    || !i.executionIds().contains(executionId)) {
                continue;
            }
            List<String> rest = i.executionIds().stream().filter(id -> !id.equals(executionId)).toList();
            if (rest.isEmpty()) {
                it.remove();
                removed++;
            } else {
                rewrite.add(Map.entry(en.getKey(), i.withRanking(i.score(), Math.max(1, i.occurrences() - 1),
                        i.firstSeen(), i.lastSeen(), rest)));
            }
        }
        rewrite.forEach(en -> byFingerprint.put(en.getKey(), en.getValue()));
        return removed;
    }

    private static boolean sameExecutions(Insight a, Insight b) {
        return a.executionIds().containsAll(b.executionIds()) && !b.executionIds().isEmpty();
    }

    /** Top-K global (visíveis, acima do score mínimo), maior prioridade primeiro. */
    public synchronized List<Insight> top(int k, double minScore) {
        return byFingerprint.values().stream()
                .filter(this::visible)
                .filter(i -> i.score() >= minScore)
                .sorted(Comparator.comparingDouble(Insight::score).reversed())
                .limit(k)
                .toList();
    }

    /** Insights que citam a execução (para a árvore/inspector daquela execução). */
    public synchronized List<Insight> forExecution(String executionId, int k) {
        return byFingerprint.values().stream()
                .filter(i -> i.executionIds().contains(executionId))
                .filter(this::visible)
                .sorted(Comparator.comparingDouble(Insight::score).reversed())
                .limit(k)
                .toList();
    }

    public synchronized List<Insight> all() {
        return new ArrayList<>(byFingerprint.values());
    }

    public synchronized Insight get(String fingerprint) {
        return byFingerprint.get(fingerprint);
    }

    /** O insight passa pelas supressões/cooldown? */
    public synchronized boolean visible(Insight i) {
        Suppression s = suppressions.get(i.fingerprint());
        if (s == null || s.action() == Action.USEFUL) {
            return true;
        }
        Instant now = Instant.now();
        return switch (s.action()) {
            case MUTE -> false;
            case EXPECTED -> Duration.between(s.at(), now).toDays() >= 7;
            case DISMISS -> Duration.between(s.at(), now).toHours() >= 24 || i.severity().ordinal() > s.severity().ordinal();
            default -> true;
        };
    }

    /** Registra feedback do dev (persistido). */
    public void feedback(String fingerprint, Action action) {
        synchronized (this) {
            Insight i = byFingerprint.get(fingerprint);
            if (action == Action.RESET) {
                suppressions.remove(fingerprint);
            } else {
                suppressions.put(fingerprint, new Suppression(action, Instant.now(),
                        i != null ? i.severity() : Insight.Severity.MEDIUM));
            }
            if (i != null && (action == Action.USEFUL || action == Action.DISMISS)) {
                int[] f = analyzerFeedback.computeIfAbsent(i.analyzer(), k -> new int[2]);
                f[action == Action.USEFUL ? 0 : 1]++;
            }
        }
        save();
    }

    /** Precisão estimada do analisador — média da Beta(3 + úteis, 1 + descartados). */
    public synchronized double precision(String analyzer) {
        int[] f = analyzerFeedback.getOrDefault(analyzer, new int[2]);
        return (3.0 + f[0]) / (4.0 + f[0] + f[1]);
    }

    public synchronized Map<String, Object> feedbackSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        analyzerFeedback.forEach((a, f) -> out.put(a, Map.of("useful", f[0], "dismissed", f[1], "precision",
                Math.round(precision(a) * 1000) / 1000.0)));
        return out;
    }

    public synchronized int suppressedCount() {
        return (int) byFingerprint.values().stream().filter(i -> !visible(i)).count();
    }

    public synchronized void clear() {
        byFingerprint.clear();
        flowCriticality.clear();
    }

    // ------------------------------------------------------------------ persistência

    private void save() {
        if (feedbackFile == null) {
            return;
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode sup = root.putObject("suppressions");
            ObjectNode an = root.putObject("analyzers");
            synchronized (this) {
                suppressions.forEach((fp, s) -> {
                    ObjectNode n = sup.putObject(fp);
                    n.put("action", s.action().name());
                    n.put("at", s.at().toString());
                    n.put("severity", s.severity().name());
                });
                analyzerFeedback.forEach((a, f) -> an.putArray(a).add(f[0]).add(f[1]));
            }
            Path parent = feedbackFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = feedbackFile.resolveSibling(feedbackFile.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(tmp, feedbackFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // feedback é conveniência local
        }
    }

    private void load() {
        if (feedbackFile == null || !Files.isRegularFile(feedbackFile)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(feedbackFile, StandardCharsets.UTF_8));
            root.path("suppressions").fields().forEachRemaining(e -> {
                try {
                    suppressions.put(e.getKey(), new Suppression(Action.valueOf(e.getValue().path("action").asText()),
                            Instant.parse(e.getValue().path("at").asText()),
                            Insight.Severity.valueOf(e.getValue().path("severity").asText("MEDIUM"))));
                } catch (RuntimeException ignored) {
                    // entrada inválida
                }
            });
            root.path("analyzers").fields().forEachRemaining(e ->
                    analyzerFeedback.put(e.getKey(), new int[] {e.getValue().path(0).asInt(), e.getValue().path(1).asInt()}));
        } catch (IOException | RuntimeException ignored) {
            // arquivo ilegível
        }
    }

    public Set<String> fingerprints() {
        synchronized (this) {
            return new LinkedHashSet<>(byFingerprint.keySet());
        }
    }
}
