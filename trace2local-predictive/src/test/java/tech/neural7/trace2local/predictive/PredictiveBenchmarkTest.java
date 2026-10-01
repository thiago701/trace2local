package tech.neural7.trace2local.predictive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.predictive.analyzers.BuiltinAnalyzers;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.decision.DecisionEngine;
import tech.neural7.trace2local.predictive.decision.IntelligenceConfig;
import tech.neural7.trace2local.predictive.history.FlowHistory;
import tech.neural7.trace2local.predictive.pipeline.PredictiveConfig;
import tech.neural7.trace2local.predictive.pipeline.PredictivePipeline;
import tech.neural7.trace2local.predictive.project.ProjectScanner;
import tech.neural7.trace2local.predictive.ranking.InsightStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BENCHMARK do dataset de cenários (ADR-013 §13): precisão, recall, taxa de
 * falso positivo por regra, custo por análise e sobrecarga no caminho de ingest.
 * O relatório vai para {@code target/benchmark-predictivo.md} (copiado para
 * {@code docs/qa/BENCHMARK-PREDITIVO.md} a cada evolução promovida).
 *
 * <p>Critério de promoção (AGENTS.md — Predictive Async Rules): precisão ≥ 0,9,
 * recall ≥ 0,9 e ZERO falso positivo nos cenários-controle.
 */
class PredictiveBenchmarkTest {

    @TempDir
    Path tmp;

    record Outcome(Scenarios.Scenario scenario, Set<String> produced, double millis) {}

    @Test
    void datasetPrecisionRecallAndFalsePositives() throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        for (Scenarios.Scenario s : Scenarios.all()) {
            outcomes.add(run(s));
        }
        // métricas por regra
        Set<String> ids = new TreeSet<>();
        outcomes.forEach(o -> {
            ids.addAll(o.scenario().expected());
            ids.addAll(o.produced());
        });
        Map<String, int[]> perId = new TreeMap<>(); // tp, fp, fn, negatives
        for (String id : ids) {
            int[] m = new int[4];
            for (Outcome o : outcomes) {
                boolean expected = o.scenario().expected().contains(id);
                boolean got = o.produced().contains(id);
                if (expected && got) {
                    m[0]++;
                } else if (!expected && got) {
                    m[1]++;
                } else if (expected) {
                    m[2]++;
                }
                if (!expected) {
                    m[3]++;
                }
            }
            perId.put(id, m);
        }
        int tp = perId.values().stream().mapToInt(m -> m[0]).sum();
        int fp = perId.values().stream().mapToInt(m -> m[1]).sum();
        int fn = perId.values().stream().mapToInt(m -> m[2]).sum();
        double precision = tp + fp == 0 ? 1 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 1 : (double) tp / (tp + fn);
        long controlFalsePositives = outcomes.stream()
                .filter(o -> o.scenario().expected().isEmpty() && !o.produced().isEmpty()).count();

        String report = report(outcomes, perId, precision, recall, controlFalsePositives);
        Path out = Path.of("target", "benchmark-predictivo.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(precision).as("precisão global").isGreaterThanOrEqualTo(0.9);
        assertThat(recall).as("recall global").isGreaterThanOrEqualTo(0.9);
        assertThat(controlFalsePositives).as("falsos positivos nos cenários-controle").isZero();
    }

    /** O caminho de ingest só faz offer(): nem analisador lento nem fila cheia o bloqueiam. */
    @Test
    void submitNeverBlocksIngestEvenWithSlowAnalyzersAndFullQueue() throws Exception {
        PredictiveAnalyzer slow = new PredictiveAnalyzer() {
            public String name() {
                return "Slow";
            }

            public Scope scope() {
                return Scope.EXECUTION;
            }

            public List<Insight> analyze(AnalysisContext ctx) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        PredictiveConfig cfg = new PredictiveConfig(true, tmp, false, 64, 2, 2000, 3000, 60_000, Set.of(), Map.of());
        try (PredictivePipeline p = new PredictivePipeline(cfg, List.of(slow), null, FlowHistory.inMemory(),
                InsightStore.inMemory(), null, List::of, e -> List.of())) {
            p.start();
            Execution e = Scenarios.orderFlow("load", java.time.Instant.now(), 30);
            int n = 20_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                p.submit(e);
            }
            double avgMicros = (System.nanoTime() - t0) / 1000.0 / n;
            Map<String, Object> st = p.status();
            System.out.printf(Locale.ROOT, "[BENCH] submit(): média %.3f µs/execução, máx %.1f µs, descartadas %s de %d (fila 64)%n",
                    avgMicros, st.get("submitMaxMicros"), st.get("dropped"), n);
            Files.writeString(Path.of("target", "benchmark-submit.txt"), String.format(Locale.ROOT,
                    "submit avg=%.3fus max=%sus dropped=%s n=%d%n", avgMicros, st.get("submitMaxMicros"), st.get("dropped"), n));
            assertThat(avgMicros).as("custo médio de submit no caminho do assembler").isLessThan(20);
            assertThat((long) st.get("dropped")).as("backpressure declarado (descarte contado)").isPositive();
        }
    }

    private Outcome run(Scenarios.Scenario s) throws Exception {
        Path dir = Files.createTempDirectory(tmp, s.name());
        ProjectScanner scanner = null;
        if (s.projectFiles() != null) {
            s.projectFiles().write(dir);
            scanner = new ProjectScanner(List.of(dir));
        }
        List<Execution> all = new ArrayList<>(s.priors());
        if (s.target() != null) {
            all.add(s.target());
        }
        List<Execution> newestFirst = new ArrayList<>(all);
        newestFirst.sort(Comparator.comparing(Execution::startedAt).reversed());
        Map<String, List<LogEntry>> logs = s.logs();
        PredictiveConfig cfg = new PredictiveConfig(true, dir, false, 256, 2, 5000, 0, 60_000, Set.of(), Map.of());
        DecisionEngine engine = new DecisionEngine(IntelligenceConfig.from(k -> null)); // determinístico (sem chave)
        try (PredictivePipeline p = new PredictivePipeline(cfg, BuiltinAnalyzers.all(), engine, FlowHistory.inMemory(),
                InsightStore.inMemory(), scanner, () -> newestFirst, e -> logs.getOrDefault(e.executionId(), List.of()))) {
            if (s.project() != null) {
                p.project(s.project());
            }
            for (Execution prior : s.priors()) {
                p.analyzeExecution(prior);
            }
            long t0 = System.nanoTime();
            List<Insight> found = switch (s.stage()) {
                case EXECUTION -> p.analyzeExecution(s.target());
                case CORPUS -> p.analyzeCorpus();
                case PROJECT -> p.analyzeProject();
            };
            double ms = (System.nanoTime() - t0) / 1e6;
            Set<String> produced = new TreeSet<>();
            found.forEach(i -> produced.add(i.id()));
            // Evidence First: nenhum insight sem evidência navegável ou observação
            for (Insight i : found) {
                assertThat(i.evidence()).as(i.id() + " evidência").isNotEmpty();
                assertThat(i.observation()).as(i.id() + " observação").isNotBlank();
                assertThat(i.confidence()).as(i.id() + " confiança").isBetween(0.0, 1.0);
            }
            return new Outcome(s, produced, ms);
        }
    }

    private static String report(List<Outcome> outcomes, Map<String, int[]> perId, double precision, double recall,
                                 long controlFp) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Benchmark — Regras Assíncronas Preditivas\n\n");
        sb.append("Motor de decisão: **Jev determinístico** (sem chave — reprodutível). Cenários: ")
                .append(outcomes.size()).append(" (").append(outcomes.stream().filter(o -> o.scenario().expected().isEmpty()).count())
                .append(" de controle).\n\n");
        sb.append(String.format(Locale.ROOT, "**Precisão global %.3f · Recall global %.3f · FP em cenários-controle: %d**%n%n",
                precision, recall, controlFp));
        sb.append("## Por regra\n\n| regra | TP | FP | FN | precisão | recall | FPR |\n|---|---|---|---|---|---|---|\n");
        perId.forEach((id, m) -> sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %.2f | %.2f | %.3f |%n", id, m[0], m[1], m[2],
                m[0] + m[1] == 0 ? 1.0 : (double) m[0] / (m[0] + m[1]),
                m[0] + m[2] == 0 ? 1.0 : (double) m[0] / (m[0] + m[2]),
                m[3] == 0 ? 0.0 : (double) m[1] / m[3])));
        sb.append("\n## Por cenário\n\n| cenário | problema inserido | esperado | produzido | ms |\n|---|---|---|---|---|\n");
        for (Outcome o : outcomes) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %.2f |%n", o.scenario().name(), o.scenario().problem(),
                    o.scenario().expected().isEmpty() ? "—" : String.join(", ", new TreeSet<>(o.scenario().expected())),
                    o.produced().isEmpty() ? "—" : String.join(", ", o.produced()), o.millis()));
        }
        double[] ms = outcomes.stream().mapToDouble(Outcome::millis).sorted().toArray();
        sb.append(String.format(Locale.ROOT, "%nCusto por análise: mediana %.2f ms · p95 %.2f ms · máx %.2f ms (worker assíncrono, fora do ingest).%n",
                ms[ms.length / 2], ms[(int) Math.floor(0.95 * (ms.length - 1))], ms[ms.length - 1]));
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return new LinkedHashMap<>();
    }
}
