package tech.neural7.trace2local.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.ExecutionStore;
import tech.neural7.trace2local.internal.LiveEvent;
import tech.neural7.trace2local.internal.LogStore;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.otel.OtelAttributeNames;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.assistant.ExecutionAssistant;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.correlation.Stats;
import tech.neural7.trace2local.predictive.correlation.Topology;
import tech.neural7.trace2local.predictive.decision.DecisionEngine;
import tech.neural7.trace2local.predictive.decision.IntelligenceConfig;
import tech.neural7.trace2local.predictive.explain.InvestigationExplainer;
import tech.neural7.trace2local.predictive.history.FlowHistory;
import tech.neural7.trace2local.predictive.pipeline.PredictiveConfig;
import tech.neural7.trace2local.predictive.pipeline.PredictivePipeline;
import tech.neural7.trace2local.predictive.project.ProjectScanner;
import tech.neural7.trace2local.predictive.ranking.InsightStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Fachada do servidor para a INTELIGÊNCIA (ADR-011/ADR-013): liga o pipeline
 * preditivo ao assembler (só um {@code offer} no caminho do evento), pré-calcula
 * o assistente de cada execução em background, e expõe topologia, histórico,
 * logs e insights em JSON para a UI.
 */
public final class PredictiveService implements AutoCloseable {

    private final ExecutionStore store;
    private final LogStore logs;
    private final BusinessGlossary glossary;
    private final DecisionEngine engine;
    private final PredictivePipeline pipeline;
    private final ExecutionAssistant assistant;
    private final InvestigationExplainer explainer;
    private final InfraService infra;
    private final Map<String, CachedAssist> assistCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedAssist> eldest) {
            return size() > 120;
        }
    };
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore assistPermits = new Semaphore(1);

    private record CachedAssist(String signature, ObjectNode json) {}

    PredictiveService(ExecutionStore store, LogStore logs, Trace2LocalConfig cfg, BusinessGlossary glossary,
                      InfraService infra, SseHub hub) {
        this.store = store;
        this.logs = logs;
        this.glossary = glossary;
        this.infra = infra;
        this.engine = new DecisionEngine(IntelligenceConfig.fromEnvironment());
        PredictiveConfig pcfg = PredictiveConfig.fromEnvironment();
        this.pipeline = new PredictivePipeline(pcfg, engine,
                new ProjectScanner(ProjectScanner.rootsFromEnvironment(cfg.infraScanDirs())),
                this::corpus, this::logsOf);
        this.assistant = new ExecutionAssistant(engine);
        this.explainer = InvestigationExplainer.fromEnvironment();
        pipeline.store().addListener(changed -> {
            ObjectNode n = JsonCodec.MAPPER.createObjectNode();
            n.put("count", changed.size());
            ArrayNode ids = n.putArray("executionIds");
            Set<String> execs = new LinkedHashSet<>();
            changed.forEach(i -> execs.addAll(i.executionIds()));
            execs.forEach(ids::add);
            ArrayNode top = n.putArray("insights");
            changed.stream().limit(5).forEach(i -> top.add(insightJson(i, false)));
            hub.broadcast("insights.updated", n);
        });
        pipeline.start();
    }

    /** Ouvinte do assembler: O(1) — enfileira; o trabalho pesado é do worker. */
    void onLiveEvent(LiveEvent event) {
        if (event instanceof LiveEvent.ExecutionCompleted c) {
            pipeline.submit(c.execution());
            prewarm(c.execution());
        }
    }

    private void prewarm(Execution e) {
        if (!assistPermits.tryAcquire()) {
            return; // já há um pré-cálculo em curso — a UI calcula sob demanda
        }
        background.submit(() -> {
            try {
                Thread.sleep(400); // debounce: deixa o ingest de logs/mutações assentar
                assist(e);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // pré-cálculo é otimização
            } finally {
                assistPermits.release();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ dados para análise

    List<Execution> corpus() {
        List<Execution> out = new ArrayList<>();
        store.recent(100).forEach(s -> store.get(s.executionId()).ifPresent(out::add));
        return out;
    }

    List<LogEntry> logsOf(Execution e) {
        return logs.forExecution(e.traceId(), requestIds(e));
    }

    static Set<String> requestIds(Execution e) {
        Set<String> ids = new LinkedHashSet<>();
        if (e.roots() != null) {
            e.roots().forEach(r -> collectRequestIds(r, ids));
        }
        return ids;
    }

    private static void collectRequestIds(Node n, Set<String> ids) {
        if (n.kind() == NodeKind.LAMBDA && n.attributes() != null) {
            String id = n.attributes().get(OtelAttributeNames.FAAS_INVOCATION_ID);
            if (id != null && !id.isBlank() && !"?".equals(id)) {
                ids.add(id);
            }
        }
        if (n.children() != null) {
            n.children().forEach(c -> collectRequestIds(c, ids));
        }
    }

    // ------------------------------------------------------------------ assistente

    /**
     * Assistente (visão técnica + executiva) da execução — com cache por assinatura.
     * Os insights preditivos são anexados SEMPRE frescos (o ranking muda com o tempo
     * e com o feedback), sobre uma cópia do bloco cacheado.
     */
    ObjectNode assist(Execution e) {
        List<LogEntry> lines = logsOf(e);
        String signature = e.status() + "|" + (e.metrics() != null ? e.metrics().nodeCount() : 0) + "|" + lines.size()
                + "|" + engine.config().effectiveMode();
        ObjectNode base = null;
        synchronized (assistCache) {
            CachedAssist c = assistCache.get(e.executionId());
            if (c != null && c.signature().equals(signature)) {
                base = c.json();
            }
        }
        if (base == null) {
            base = assistant.analyze(e, lines, glossary.entries());
            // nó → componente da Anatomia (o mesmo id da topologia): destaque cruzado na UI
            ObjectNode comps = base.putObject("components");
            FlowView.of(e).steps().forEach(st -> comps.put(st.node().nodeId(), st.component()));
            synchronized (assistCache) {
                assistCache.put(e.executionId(), new CachedAssist(signature, base));
            }
        }
        ObjectNode json = base.deepCopy();
        ArrayNode ins = json.putArray("insights");
        pipeline.store().forExecution(e.executionId(), 6).forEach(i -> ins.add(insightJson(i, true)));
        return json;
    }

    /** Insights da execução (sempre frescos — o ranking muda com o tempo). */
    ArrayNode insightsFor(String executionId, int limit) {
        ArrayNode arr = JsonCodec.MAPPER.createArrayNode();
        pipeline.store().forExecution(executionId, limit).forEach(i -> arr.add(insightJson(i, true)));
        return arr;
    }

    ObjectNode topInsights(int limit) {
        ObjectNode n = JsonCodec.MAPPER.createObjectNode();
        ArrayNode arr = n.putArray("insights");
        pipeline.store().top(limit, 0.05).forEach(i -> arr.add(insightJson(i, true)));
        n.put("suppressed", pipeline.store().suppressedCount());
        n.put("total", pipeline.store().all().size());
        return n;
    }

    ObjectNode explain(String fingerprint, boolean llm) {
        Insight i = pipeline.store().get(fingerprint);
        if (i == null) {
            return null;
        }
        InvestigationExplainer.Explanation ex = explainer.explain(i, llm);
        ObjectNode n = JsonCodec.MAPPER.createObjectNode();
        n.put("fingerprint", fingerprint);
        n.put("text", ex.text());
        n.put("engine", ex.engine());
        n.put("model", ex.model());
        n.put("llmAvailable", explainer.llmAvailable());
        return n;
    }

    boolean feedback(String fingerprint, String action) {
        InsightStore.Action a;
        try {
            a = InsightStore.Action.valueOf(action.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return false;
        }
        if (pipeline.store().get(fingerprint) == null) {
            return false;
        }
        pipeline.store().feedback(fingerprint, a);
        return true;
    }

    // ------------------------------------------------------------------ status

    ObjectNode status() {
        ObjectNode n = JsonCodec.MAPPER.createObjectNode();
        n.set("engine", JsonCodec.MAPPER.valueToTree(engine.status()));
        n.set("pipeline", JsonCodec.MAPPER.valueToTree(pipeline.status()));
        n.set("feedback", JsonCodec.MAPPER.valueToTree(pipeline.store().feedbackSummary()));
        ObjectNode llm = n.putObject("llm");
        llm.put("available", explainer.llmAvailable());
        llm.put("description", explainer.llmDescription());
        n.put("glossaryTerms", glossary.entries().size());
        ObjectNode logStats = n.putObject("logs");
        logStats.put("total", logs.total());
        logStats.put("dropped", logs.dropped());
        return n;
    }

    // ------------------------------------------------------------------ logs

    ObjectNode logsJson(Execution e) {
        List<LogEntry> lines = logsOf(e);
        ObjectNode root = JsonCodec.MAPPER.createObjectNode();
        root.put("executionId", e.executionId());
        root.put("traceId", e.traceId());
        ArrayNode reqs = root.putArray("requestIds");
        requestIds(e).forEach(reqs::add);
        Instant start = e.startedAt();
        // janela de cada invocação Lambda (RequestId → [início, fim] em ms desde o início da execução)
        Map<String, long[]> invocations = new LinkedHashMap<>();
        if (e.roots() != null && start != null) {
            e.roots().forEach(r -> collectInvocations(r, start, invocations));
        }
        Map<String, Integer> bySource = new LinkedHashMap<>();
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        List<ObjectNode> out = new ArrayList<>();
        int i = 0;
        int aligned = 0;
        for (LogEntry l : lines) {
            ObjectNode n = JsonCodec.MAPPER.createObjectNode();
            n.put("index", i++);
            n.put("timestamp", l.timestamp() != null ? l.timestamp().toString() : null);
            long offset = start != null && l.timestamp() != null ? Duration.between(start, l.timestamp()).toMillis() : 0;
            long[] win = l.requestId() != null ? invocations.get(l.requestId()) : null;
            long shown = offset;
            if (win != null) {
                // Linhas de plataforma (START/END/REPORT) presas às bordas do span da invocação;
                // linhas do CloudWatch fora da janela são trazidas para dentro dela. O LocalStack
                // carimba as linhas na INGESTÃO (depois da invocação) — sem isso a narrativa
                // mostraria START depois do log da aplicação. O valor observado fica em observedOffsetMs.
                String msg = l.message() != null ? l.message() : "";
                if (l.isPlatformLine()) {
                    shown = msg.startsWith("END") || msg.startsWith("REPORT") ? win[1] : win[0];
                } else if (l.source() == LogEntry.LogSource.CLOUDWATCH) {
                    shown = Math.max(win[0], Math.min(win[1], offset));
                }
            }
            n.put("offsetMs", shown);
            if (shown != offset) {
                n.put("observedOffsetMs", offset);
                n.put("aligned", true);
                aligned++;
            }
            n.put("level", l.level());
            n.put("logger", l.logger());
            n.put("message", l.message());
            n.put("spanId", l.spanId());
            n.put("requestId", l.requestId());
            n.put("logGroup", l.logGroup());
            n.put("logStream", l.logStream());
            n.put("source", l.source() != null ? l.source().name() : null);
            n.put("platform", l.isPlatformLine());
            out.add(n);
            bySource.merge(l.source() != null ? l.source().name() : "?", 1, Integer::sum);
            if (l.logGroup() != null) {
                groups.computeIfAbsent(l.logGroup(), k -> new LinkedHashSet<>()).add(String.valueOf(l.logStream()));
            }
        }
        // ordem narrativa: pelo instante exibido (estável — empates mantêm a ordem de chegada)
        out.sort(java.util.Comparator.<ObjectNode>comparingLong(n -> n.get("offsetMs").asLong())
                .thenComparingInt(PredictiveService::platformRank));
        ArrayNode arr = root.putArray("lines");
        out.forEach(arr::add);
        root.put("alignedLines", aligned);
        root.set("bySource", JsonCodec.MAPPER.valueToTree(bySource));
        ArrayNode g = root.putArray("groups");
        groups.forEach((group, streams) -> {
            ObjectNode gn = g.addObject();
            gn.put("logGroup", group);
            ArrayNode st = gn.putArray("logStreams");
            streams.forEach(st::add);
        });
        root.put("dropped", logs.dropped());
        return root;
    }

    /** Empate no mesmo instante: START/INIT antes do log da aplicação; END/REPORT depois. */
    private static int platformRank(ObjectNode n) {
        if (!n.path("platform").asBoolean()) {
            return 0;
        }
        String m = n.path("message").asText("");
        return m.startsWith("END") || m.startsWith("REPORT") ? 1 : -1;
    }

    private static void collectInvocations(Node n, Instant start, Map<String, long[]> out) {
        if (n.kind() == NodeKind.LAMBDA && n.attributes() != null && n.startedAt() != null) {
            String id = n.attributes().get(OtelAttributeNames.FAAS_INVOCATION_ID);
            if (id != null && !id.isBlank()) {
                long s0 = Duration.between(start, n.startedAt()).toMillis();
                long s1 = s0 + (n.totalTime() != null ? n.totalTime().toMillis() : 0);
                out.put(id, new long[] {s0, s1});
            }
        }
        if (n.children() != null) {
            n.children().forEach(c -> collectInvocations(c, start, out));
        }
    }

    // ------------------------------------------------------------------ topologia

    /** Anatomia do ecossistema: componentes por zona + arestas + recursos declarados não observados. */
    ObjectNode topology() {
        List<Execution> corpus = corpus();
        Topology t = Topology.of(corpus);
        ObjectNode root = JsonCodec.MAPPER.createObjectNode();
        root.put("executions", t.executions());
        root.put("flows", t.flows().size());
        Map<String, Integer> insightsByComponent = new LinkedHashMap<>();
        Map<String, String> topInsight = new LinkedHashMap<>();
        for (Insight i : pipeline.store().top(50, 0.05)) {
            for (String c : i.affectedComponents()) {
                insightsByComponent.merge(c, 1, Integer::sum);
                topInsight.putIfAbsent(c, i.category().name());
            }
        }
        ArrayNode comps = root.putArray("components");
        for (Topology.Component c : t.components().values()) {
            ObjectNode n = comps.addObject();
            n.put("id", c.id);
            n.put("kind", c.kind);
            n.put("zone", c.zone);
            n.put("label", c.label);
            n.put("calls", c.calls);
            n.put("errors", c.errors);
            n.put("p50", Math.round(c.p50()));
            n.put("p95", Math.round(c.p95()));
            n.put("flows", c.flows.size());
            n.put("neighbors", c.neighbors.size());
            n.put("insights", insightsByComponent.getOrDefault(c.id, 0));
            n.put("insightCategory", topInsight.get(c.id));
            ArrayNode ex = n.putArray("executionIds");
            c.executions.stream().limit(30).forEach(ex::add);
        }
        // fronteira DECLARADA: recurso no IaC que o acervo nunca observou
        for (JsonNode entry : infra.snapshot().path("entries")) {
            if (!"RECURSO_TERRAFORM".equals(entry.path("type").asText())) {
                continue;
            }
            String id = declaredId(entry.path("name").asText(), entry.path("value").asText());
            if (id != null && !t.components().containsKey(id)) {
                ObjectNode n = comps.addObject();
                n.put("id", id);
                n.put("kind", kindOfDeclared(id));
                n.put("zone", "declared");
                n.put("label", id.substring(id.indexOf(':') + 1));
                n.put("calls", 0);
                n.put("errors", 0);
                JsonNode src = entry.path("sources").path(0);
                n.put("source", src.path("file").asText() + ":" + src.path("line").asInt());
                n.putArray("executionIds");
            }
        }
        ArrayNode edges = root.putArray("edges");
        for (Topology.Edge e : t.edges().values()) {
            ObjectNode n = edges.addObject();
            n.put("from", e.from);
            n.put("to", e.to);
            n.put("async", e.async);
            n.put("calls", e.calls);
            n.put("errors", e.errors);
            n.put("waitP50", e.waits.isEmpty() ? 0 : Math.round(Stats.median(e.waits)));
        }
        ObjectNode zones = root.putObject("zones");
        zones.put("core", "Código da aplicação (serviços, funções, regras)");
        zones.put("boundary", "Fronteira local: recursos AWS/LocalStack (tabelas, filas, tópicos)");
        zones.put("external", "Fronteira externa: parceiros e APIs fora da máquina");
        zones.put("declared", "Declarado no IaC e nunca observado em execução");
        return root;
    }

    private static String declaredId(String tfType, String name) {
        // nome dinâmico (each.key, ${local.x}-api, var.x): não dá para afirmar que "nunca foi observado"
        if (name == null || name.isBlank() || name.contains("${") || name.startsWith("each.")
                || name.startsWith("var.") || name.startsWith("local.")) {
            return null;
        }
        return switch (tfType) {
            case "dynamodb_table" -> "dynamodb:" + name;
            case "sqs_queue" -> "sqs:" + name;
            case "sns_topic" -> "sns:" + name;
            case "lambda_function" -> "lambda:" + name;
            default -> null;
        };
    }

    private static String kindOfDeclared(String id) {
        if (id.startsWith("dynamodb:")) {
            return "DYNAMODB";
        }
        if (id.startsWith("sqs:")) {
            return "SQS";
        }
        if (id.startsWith("sns:")) {
            return "SNS";
        }
        return "LAMBDA";
    }

    // ------------------------------------------------------------------ histórico

    ObjectNode history(String flowKey) {
        FlowHistory h = pipeline.history();
        ObjectNode root = JsonCodec.MAPPER.createObjectNode();
        ArrayNode flows = root.putArray("flows");
        for (var e : h.all().entrySet()) {
            if (flowKey != null && !flowKey.equals(e.getKey())) {
                continue;
            }
            List<Long> totals = e.getValue().stream().map(FlowHistory.Sample::totalMs).toList();
            ObjectNode f = flows.addObject();
            f.put("flowKey", e.getKey());
            f.put("samples", totals.size());
            f.put("p50", Math.round(Stats.median(totals)));
            f.put("p95", Math.round(Stats.percentile(totals, 95)));
            FlowHistory.Comparison c = h.compare(e.getKey(), 5);
            if (c != null) {
                ObjectNode cmp = f.putObject("comparison");
                cmp.put("mode", c.mode());
                cmp.put("baselineP50", Math.round(c.baselineP50()));
                cmp.put("recentP50", Math.round(c.recentP50()));
                cmp.put("change", Double.isNaN(c.change()) ? 0 : Math.round(c.change() * 1000) / 1000.0);
            }
            ArrayNode series = f.putArray("series");
            for (FlowHistory.Sample s : e.getValue().subList(Math.max(0, e.getValue().size() - 60), e.getValue().size())) {
                series.addObject().put("executionId", s.executionId()).put("at", s.at().toString())
                        .put("totalMs", s.totalMs()).put("queueWaitMs", s.queueWaitMs()).put("dbMs", s.dbMs())
                        .put("errors", s.errors());
            }
        }
        return root;
    }

    // ------------------------------------------------------------------ json

    static ObjectNode insightJson(Insight i, boolean full) {
        ObjectNode n = JsonCodec.MAPPER.createObjectNode();
        n.put("id", i.id());
        n.put("fingerprint", i.fingerprint());
        n.put("category", i.category().name());
        n.put("categoryLabel", i.category().label());
        n.put("glyph", i.category().glyph());
        n.put("severity", i.severity().name());
        n.put("confidence", i.confidence());
        n.put("confidenceBand", i.confidenceBand());
        n.put("nature", i.nature().name());
        n.put("title", i.title());
        n.put("observation", i.observation());
        n.put("score", i.score());
        n.put("occurrences", i.occurrences());
        n.put("analyzer", i.analyzer());
        n.put("decidedBy", i.decidedBy());
        ArrayNode comps = n.putArray("affectedComponents");
        i.affectedComponents().forEach(comps::add);
        ArrayNode execs = n.putArray("executionIds");
        i.executionIds().forEach(execs::add);
        if (full) {
            n.put("correlation", i.correlation());
            n.put("hypothesis", i.hypothesis());
            ArrayNode recs = n.putArray("recommendations");
            i.recommendations().forEach(recs::add);
            ArrayNode ev = n.putArray("evidence");
            for (Evidence e : i.evidence()) {
                ObjectNode en = ev.addObject();
                en.put("kind", e.kind().name());
                en.put("label", e.label());
                en.put("value", e.value());
                if (e.ref() != null) {
                    ObjectNode r = en.putObject("ref");
                    r.put("executionId", e.ref().executionId());
                    r.put("nodeId", e.ref().nodeId());
                    r.put("file", e.ref().file());
                    r.put("line", e.ref().line());
                    r.put("component", e.ref().component());
                }
            }
            n.put("firstSeen", i.firstSeen() != null ? i.firstSeen().toString() : null);
            n.put("lastSeen", i.lastSeen() != null ? i.lastSeen().toString() : null);
        }
        return n;
    }

    void clear() {
        pipeline.store().clear();
        synchronized (assistCache) {
            assistCache.clear();
        }
    }

    PredictivePipeline pipeline() {
        return pipeline;
    }

    @Override
    public void close() {
        pipeline.close();
        background.shutdownNow();
    }
}
