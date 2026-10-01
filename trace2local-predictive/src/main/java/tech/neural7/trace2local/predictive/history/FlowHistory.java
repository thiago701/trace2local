package tech.neural7.trace2local.predictive.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.correlation.Stats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * HISTÓRICO LOCAL dos fluxos (ADR-013 §11): por assinatura de fluxo guarda as
 * últimas amostras (duração ponta a ponta, espera em fila, banco, nº de nós,
 * erros, componentes) e calcula BASELINE (p50/p95) e janela RECENTE — a base
 * das detecções de regressão, outlier e mudança de forma.
 *
 * <p>Local-first/privacy-first: persiste em {@code .trace2local/history/flows.json}
 * só números, nomes de componentes e ids de execução — nenhum payload, log ou
 * valor de dado. Desligável ({@code TRACE2LOCAL_HISTORY=off}).
 */
public final class FlowHistory {

    public static final int MAX_SAMPLES_PER_FLOW = 200;
    public static final int MAX_FLOWS = 500;

    /** Uma amostra numérica de execução. */
    public record Sample(String executionId, Instant at, long totalMs, long queueWaitMs, long dbMs, int dbCalls,
                         int nodes, int errors, String shape, Set<String> components, Set<String> consumed) {}

    /** Baseline × recente de um fluxo. */
    public record Comparison(String flowKey, int baselineCount, int recentCount,
                             double baselineP50, double baselineP95, double recentP50, double recentP95,
                             double change, double robustZ, double baselineQueueP50, double recentQueueP50,
                             double baselineDbP50, double recentDbP50, String mode) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Deque<Sample>> flows = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<Sample>> eldest) {
            return size() > MAX_FLOWS;
        }
    };
    private final Path file;
    private volatile int dirty;

    /** @param file arquivo de persistência, ou {@code null} para histórico só em memória */
    public FlowHistory(Path file) {
        this.file = file;
        load();
    }

    public static FlowHistory inMemory() {
        return new FlowHistory(null);
    }

    /**
     * Registra a execução. Idempotente por executionId: uma re-conclusão da mesma
     * execução (continuação tardia fundiu o consumidor) SUBSTITUI a amostra antiga
     * — em qualquer fluxo, já que a chave do fluxo pode mudar (ex.: "[falha]").
     */
    public synchronized Sample record(FlowView flow) {
        String key = flow.flowKey();
        String id = flow.execution().executionId();
        int steps = flow.steps().size();
        for (Map.Entry<String, Deque<Sample>> en : flows.entrySet()) {
            for (Sample s : en.getValue()) {
                if (s.executionId().equals(id) && s.nodes() == steps && key.equals(en.getKey())) {
                    return s; // mesma forma: nada mudou
                }
            }
        }
        flows.values().forEach(d -> d.removeIf(s -> s.executionId().equals(id)));
        flows.values().removeIf(Deque::isEmpty);
        Deque<Sample> samples = flows.computeIfAbsent(key, k -> new ArrayDeque<>());
        Set<String> comps = new TreeSet<>();
        flow.steps().forEach(s -> comps.add(s.component()));
        // produtores que tiveram consumidor observado (base da predição "consumidor parou")
        Set<String> consumed = new TreeSet<>();
        for (FlowView.Step p : flow.steps()) {
            if (p.producer() && flow.steps().stream().anyMatch(c -> p.node().nodeId().equals(c.parentId()) && c.asyncConsumer())) {
                consumed.add(p.component());
            }
        }
        Sample sample = new Sample(id,
                flow.execution().startedAt() != null ? flow.execution().startedAt() : Instant.now(),
                flow.endToEndMs(), flow.totalQueueWaitMs(), flow.totalDbMs(), flow.dbCalls(),
                flow.steps().size(), (int) flow.steps().stream().filter(FlowView.Step::failed).count(),
                flow.shapeHash(), comps, consumed);
        Sample last = samples.peekLast();
        samples.addLast(sample);
        if (last != null && last.at().isAfter(sample.at())) {
            // amostra substituída (re-conclusão) volta à sua posição cronológica
            List<Sample> ordered = new ArrayList<>(samples);
            ordered.sort(java.util.Comparator.comparing(Sample::at));
            samples.clear();
            samples.addAll(ordered);
        }
        while (samples.size() > MAX_SAMPLES_PER_FLOW) {
            samples.removeFirst();
        }
        dirty++;
        return sample;
    }

    /** Amostras do fluxo (mais antigas primeiro), excluindo opcionalmente uma execução. */
    public synchronized List<Sample> samples(String flowKey) {
        Deque<Sample> s = flows.get(flowKey);
        return s == null ? List.of() : new ArrayList<>(s);
    }

    public synchronized Map<String, List<Sample>> all() {
        Map<String, List<Sample>> out = new LinkedHashMap<>();
        flows.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }

    /**
     * Compara a janela RECENTE com o BASELINE. Preferência: dia anterior × hoje
     * ("ontem p50 = 320 ms, hoje p50 = 690 ms"); sem dados de ontem, as amostras
     * mais antigas × as {@code recentWindow} mais novas.
     */
    public synchronized Comparison compare(String flowKey, int recentWindow) {
        List<Sample> s = samples(flowKey);
        if (s.size() < 2) {
            return null;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Sample> before = new ArrayList<>();
        List<Sample> recent = new ArrayList<>();
        for (Sample x : s) {
            if (LocalDate.ofInstant(x.at(), ZoneOffset.UTC).isBefore(today)) {
                before.add(x);
            } else {
                recent.add(x);
            }
        }
        String mode = "dia-anterior";
        if (before.size() < 5 || recent.size() < 3) {
            int w = Math.min(recentWindow, s.size() / 2);
            if (w < 1) {
                return null;
            }
            before = s.subList(0, s.size() - w);
            recent = s.subList(s.size() - w, s.size());
            mode = "janela";
        }
        List<Long> bt = before.stream().map(Sample::totalMs).toList();
        List<Long> rt = recent.stream().map(Sample::totalMs).toList();
        double bP50 = Stats.median(bt);
        double rP50 = Stats.median(rt);
        return new Comparison(flowKey, before.size(), recent.size(), bP50, Stats.percentile(bt, 95), rP50,
                Stats.percentile(rt, 95), Stats.relativeChange(bP50, rP50), Stats.robustZ(rP50, bt),
                Stats.median(before.stream().map(Sample::queueWaitMs).toList()),
                Stats.median(recent.stream().map(Sample::queueWaitMs).toList()),
                Stats.median(before.stream().map(Sample::dbMs).toList()),
                Stats.median(recent.stream().map(Sample::dbMs).toList()), mode);
    }

    // ------------------------------------------------------------------ persistência

    /** Grava se houve mudança (chamado pelo worker do pipeline, nunca no ingest). */
    public synchronized void flush() {
        if (file == null || dirty == 0) {
            return;
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", 1);
            ObjectNode fl = root.putObject("flows");
            flows.forEach((key, samples) -> {
                ArrayNode arr = fl.putArray(key);
                for (Sample s : samples) {
                    ObjectNode n = arr.addObject();
                    n.put("id", s.executionId());
                    n.put("at", s.at().toString());
                    n.put("t", s.totalMs());
                    n.put("q", s.queueWaitMs());
                    n.put("d", s.dbMs());
                    n.put("dc", s.dbCalls());
                    n.put("n", s.nodes());
                    n.put("e", s.errors());
                    n.put("sh", s.shape());
                    ArrayNode comps = n.putArray("c");
                    s.components().forEach(comps::add);
                    ArrayNode cs = n.putArray("cs");
                    s.consumed().forEach(cs::add);
                }
            });
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = 0;
        } catch (IOException | RuntimeException ignored) {
            // histórico é conveniência: falha de disco não derruba a análise
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            root.path("flows").fields().forEachRemaining(e -> {
                Deque<Sample> d = new ArrayDeque<>();
                for (JsonNode n : e.getValue()) {
                    Set<String> comps = new TreeSet<>();
                    n.path("c").forEach(c -> comps.add(c.asText()));
                    Set<String> consumed = new TreeSet<>();
                    n.path("cs").forEach(c -> consumed.add(c.asText()));
                    try {
                        d.addLast(new Sample(n.path("id").asText(), Instant.parse(n.path("at").asText()),
                                n.path("t").asLong(), n.path("q").asLong(), n.path("d").asLong(), n.path("dc").asInt(),
                                n.path("n").asInt(), n.path("e").asInt(), n.path("sh").asText(""), comps, consumed));
                    } catch (RuntimeException skip) {
                        // amostra corrompida é ignorada
                    }
                }
                if (!d.isEmpty()) {
                    flows.put(e.getKey(), d);
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // arquivo ilegível = histórico zerado (declarado no status)
        }
    }

    public synchronized int flowCount() {
        return flows.size();
    }

    public synchronized int sampleCount() {
        return flows.values().stream().mapToInt(Deque::size).sum();
    }

    public synchronized void clear() {
        flows.clear();
        dirty++;
    }

    public Path file() {
        return file;
    }
}
