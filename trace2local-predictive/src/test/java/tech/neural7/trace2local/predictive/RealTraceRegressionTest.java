package tech.neural7.trace2local.predictive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.internal.ExecutionJson;
import tech.neural7.trace2local.internal.LogEntryJson;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.assistant.ExecutionAssistant;
import tech.neural7.trace2local.predictive.analyzers.BuiltinAnalyzers;
import tech.neural7.trace2local.predictive.decision.DecisionEngine;
import tech.neural7.trace2local.predictive.decision.IntelligenceConfig;
import tech.neural7.trace2local.predictive.history.FlowHistory;
import tech.neural7.trace2local.predictive.pipeline.PredictiveConfig;
import tech.neural7.trace2local.predictive.pipeline.PredictivePipeline;
import tech.neural7.trace2local.predictive.ranking.InsightStore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regressão com TRACES REAIS (não sintéticos): capturados do cenário
 * {@code examples/lambda-sqs} — AWS Lambda java21 + DynamoDB + SQS + event source
 * mapping + CloudWatch Logs no LocalStack 4.2 — via {@code scripts/real-traces/capture.py}.
 *
 * <ul>
 *   <li><b>run-1-fresh</b>: tabelas vazias — 3 pedidos felizes (consumidor real fundido
 *       na árvore), 1 recusa de crédito, idempotência (cria + reentrega recusada).
 *       NÃO pode haver achado de idempotência (era falso positivo antes do fix do
 *       {@code before = {}}).</li>
 *   <li><b>run-2-reprocess</b>: as MESMAS jornadas de novo (reprocessamento real):
 *       o order-processor regrava pedidos sem condição (IDEM-001 verdadeiro) e o
 *       order-billing cobra de novo (IDEM-002 verdadeiro); a guarda condicional do
 *       idempotent-processor protege (nenhum achado na tabela idempotency).</li>
 * </ul>
 */
class RealTraceRegressionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static List<Captured> run1;
    private static List<Captured> run2;

    record Captured(Execution execution, List<LogEntry> logs) {}

    @BeforeAll
    static void load() throws Exception {
        run1 = load("real-traces/lambda-sqs/run-1-fresh");
        run2 = load("real-traces/lambda-sqs/run-2-reprocess");
    }

    static List<Captured> load(String dir) throws IOException, URISyntaxException {
        Path root = Paths.get(RealTraceRegressionTest.class.getClassLoader().getResource(dir).toURI());
        List<Captured> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonNode doc;
                try (InputStream in = Files.newInputStream(f)) {
                    doc = JSON.readTree(in);
                }
                List<LogEntry> logs = new ArrayList<>();
                for (JsonNode l : doc.path("logs")) {
                    LogEntry e = LogEntryJson.fromJson(l);
                    if (e != null) {
                        logs.add(e);
                    }
                }
                out.add(new Captured(ExecutionJson.fromJson(doc), logs));
            }
        }
        out.sort(Comparator.comparing(c -> c.execution().startedAt()));
        return out;
    }

    /** Alimenta o pipeline na ordem real (o acervo cresce como no Station). */
    static List<Insight> replay(List<Captured> traces) {
        Map<String, List<LogEntry>> logs = new LinkedHashMap<>();
        traces.forEach(c -> logs.put(c.execution().executionId(), c.logs()));
        List<Execution> corpus = new ArrayList<>();
        PredictiveConfig cfg = new PredictiveConfig(true, null, false, 256, 2, 5000, 0, 60_000, Set.of(), Map.of());
        DecisionEngine engine = new DecisionEngine(IntelligenceConfig.from(k -> null));
        try (PredictivePipeline p = new PredictivePipeline(cfg, BuiltinAnalyzers.all(), engine, FlowHistory.inMemory(),
                InsightStore.inMemory(), null, () -> corpus.reversed(), e -> logs.getOrDefault(e.executionId(), List.of()))) {
            for (Captured c : traces) {
                corpus.add(c.execution());
                p.analyzeExecution(c.execution());
            }
            p.analyzeCorpus();
            return p.store().all();
        }
    }

    private static Set<String> ids(List<Insight> insights) {
        Set<String> s = new TreeSet<>();
        insights.forEach(i -> s.add(i.id()));
        return s;
    }

    private static boolean hasConsumerUnderQueue(Execution e) {
        for (Node r : e.roots()) {
            for (Node c : r.children()) {
                if (c.kind() == NodeKind.SQS && c.children().stream().anyMatch(x -> x.kind() == NodeKind.LAMBDA)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void realAsyncConsumerIsPartOfTheProducerTree() {
        List<Execution> happy = run1.stream().map(Captured::execution)
                .filter(e -> e.roots().get(0).label().equals("order-processor") && e.status().name().equals("COMPLETED")).toList();
        assertThat(happy).hasSize(3);
        assertThat(happy).allMatch(RealTraceRegressionTest::hasConsumerUnderQueue);
        assertThat(happy).allMatch(e -> e.warnings().isEmpty());
        // logs CloudWatch reais das DUAS invocações (produtor e consumidor) vieram junto
        for (Captured c : run1) {
            if (hasConsumerUnderQueue(c.execution())) {
                assertThat(c.logs().stream().filter(l -> l.message().startsWith("START RequestId")).count()).isEqualTo(2);
            }
        }
    }

    @Test
    void freshRunHasNoIdempotencyFindings() {
        List<Insight> found = replay(run1);
        assertThat(ids(found)).doesNotContain("IDEM-001", "IDEM-002", "ASYNC-ORPH-001", "PRED-CONS-001");
        // a espera real na fila (event source mapping + cold start) domina o fluxo
        assertThat(ids(found)).contains("PERF-ASYNC-001");
        assertThat(found).allMatch(i -> !i.evidence().isEmpty() && !i.observation().isBlank());
    }

    @Test
    void reprocessingRevealsTheRealIdempotencyGaps() {
        List<Captured> both = new ArrayList<>(run1);
        both.addAll(run2);
        List<Insight> found = replay(both);
        assertThat(ids(found)).contains("IDEM-001", "IDEM-002");
        Insight overwrite = found.stream().filter(i -> i.id().equals("IDEM-001") && i.severity() == Insight.Severity.HIGH)
                .findFirst().orElseThrow();
        assertThat(overwrite.affectedComponents()).contains("dynamodb:orders");
        Insight duplicate = found.stream().filter(i -> i.id().equals("IDEM-002")).findFirst().orElseThrow();
        assertThat(duplicate.observation()).contains("order-billing");
        // a guarda condicional do idempotent-processor funciona: nenhum achado na tabela dela
        assertThat(found).noneMatch(i -> i.id().startsWith("IDEM") && i.affectedComponents().contains("dynamodb:idempotency"));
    }

    @Test
    void assistantReadsTheRealOutcomesLikeAnAnalystWould() {
        ExecutionAssistant assistant = new ExecutionAssistant(new DecisionEngine(IntelligenceConfig.from(k -> null)));
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (Captured c : run1) {
            ObjectNode a = assistant.analyze(c.execution(), c.logs(), Map.of());
            String root = c.execution().roots().get(0).label();
            outcomes.merge(root + "/" + c.execution().status(), a.path("executive").path("outcome").path("choice").asText(),
                    (x, y) -> x.equals(y) ? x : x + "|" + y);
        }
        assertThat(outcomes).containsEntry("order-processor/COMPLETED", "sucesso")
                .containsEntry("order-processor/FAILED", "falha-de-negocio")
                .containsEntry("idempotent-processor/COMPLETED", "sucesso")
                .containsEntry("idempotent-processor/FAILED", "recusa-protegida");
    }
}
