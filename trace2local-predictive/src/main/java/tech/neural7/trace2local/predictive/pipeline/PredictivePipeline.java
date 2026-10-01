package tech.neural7.trace2local.predictive.pipeline;

import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.predictive.analyzers.BuiltinAnalyzers;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.decision.DecisionEngine;
import tech.neural7.trace2local.predictive.decision.DecisionPort;
import tech.neural7.trace2local.predictive.history.FlowHistory;
import tech.neural7.trace2local.predictive.project.ProjectScanner;
import tech.neural7.trace2local.predictive.project.ProjectSnapshot;
import tech.neural7.trace2local.predictive.ranking.InsightStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Pipeline ASSÍNCRONO das Regras Preditivas (ADR-013 §2):
 * <pre>
 *  assembler ─(ExecutionCompleted)→ submit()  ← O(1), nunca bloqueia (fila limitada; cheia = descarte CONTADO)
 *                                      │
 *                     worker (virtual thread, lotes de até 32 — debounce natural)
 *                                      │
 *            ┌── histórico (baseline) ─┤
 *            │   analisadores EXECUTION em paralelo controlado (semáforo) + timeout por analisador
 *            │   analisadores CORPUS com debounce · PROJECT por mudança de mtime
 *            └──────────────→ InsightStore (dedupe, cooldown, ranking) → ouvintes (SSE)
 * </pre>
 * O caminho de ingest/assembler NUNCA espera análise nem modelo — a única
 * operação síncrona é {@code queue.offer}.
 */
public final class PredictivePipeline implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PredictivePipeline.class.getName());

    private final PredictiveConfig cfg;
    private final List<PredictiveAnalyzer> analyzers;
    private final DecisionEngine engine;
    private final DecisionPort port;
    private final FlowHistory history;
    private final InsightStore store;
    private final ProjectScanner scanner;
    private final Supplier<List<Execution>> corpus;
    private final Function<Execution, List<LogEntry>> logsOf;
    private final BlockingQueue<Execution> queue;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private volatile ProjectSnapshot project = ProjectSnapshot.EMPTY;
    private volatile long projectFingerprint = -1;
    private volatile long lastProjectCheck;
    private volatile long lastCorpusRun;
    private volatile boolean corpusDirty;
    private volatile boolean running = true;
    private Thread worker;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong analyzed = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong submitNanosMax = new AtomicLong();
    private final Map<String, long[]> perAnalyzer = new ConcurrentHashMap<>(); // nome → [execuções, nanos totais, insights]
    private volatile String lastError;

    public PredictivePipeline(PredictiveConfig cfg, DecisionEngine engine, ProjectScanner scanner,
                              Supplier<List<Execution>> corpus, Function<Execution, List<LogEntry>> logsOf) {
        this(cfg, defaultAnalyzers(cfg), engine, new FlowHistory(cfg.historyFile()), new InsightStore(cfg.feedbackFile()),
                scanner, corpus, logsOf);
    }

    public PredictivePipeline(PredictiveConfig cfg, List<PredictiveAnalyzer> analyzers, DecisionEngine engine,
                              FlowHistory history, InsightStore store, ProjectScanner scanner,
                              Supplier<List<Execution>> corpus, Function<Execution, List<LogEntry>> logsOf) {
        this.cfg = cfg;
        this.analyzers = List.copyOf(analyzers);
        this.engine = engine;
        this.port = new DecisionPort(engine);
        this.history = history;
        this.store = store;
        this.scanner = scanner;
        this.corpus = corpus != null ? corpus : List::of;
        this.logsOf = logsOf != null ? logsOf : e -> List.of();
        this.queue = new ArrayBlockingQueue<>(cfg.queueCapacity());
        this.permits = new Semaphore(cfg.parallelism());
    }

    /** Analisadores embutidos + plugins via ServiceLoader, menos os desligados. */
    public static List<PredictiveAnalyzer> defaultAnalyzers(PredictiveConfig cfg) {
        List<PredictiveAnalyzer> all = new ArrayList<>(BuiltinAnalyzers.all());
        try {
            for (PredictiveAnalyzer plugin : ServiceLoader.load(PredictiveAnalyzer.class)) {
                all.add(plugin);
            }
        } catch (Throwable t) {
            LOG.warning("plugin de analisador ignorado: " + t);
        }
        all.removeIf(a -> cfg.disabled().contains(a.name()));
        return all;
    }

    public void start() {
        if (!cfg.enabled()) {
            return;
        }
        worker = Thread.ofVirtual().name("trace2local-predictive").start(this::loop);
    }

    /**
     * Enfileira uma execução concluída. O(1), sem bloqueio, sem I/O — seguro
     * para ser chamado do ouvinte do assembler. Fila cheia ⇒ descarte contado.
     */
    public void submit(Execution e) {
        if (!cfg.enabled() || e == null || e.status() == ExecutionStatus.RUNNING) {
            return;
        }
        long t0 = System.nanoTime();
        submitted.incrementAndGet();
        if (!queue.offer(e)) {
            dropped.incrementAndGet();
        }
        long dt = System.nanoTime() - t0;
        submitNanosMax.accumulateAndGet(dt, Math::max);
    }

    private void loop() {
        while (running) {
            try {
                Execution first = queue.poll(1, TimeUnit.SECONDS);
                List<Execution> batch = new ArrayList<>();
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 31);
                }
                // debounce: a mesma execução repetida no lote (snapshot + completed) roda uma vez
                Map<String, Execution> unique = new LinkedHashMap<>();
                batch.forEach(x -> unique.put(x.executionId(), x));
                maybeRescanProject();
                for (Execution e : unique.values()) {
                    analyzeExecution(e);
                }
                maybeRunCorpus();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                failures.incrementAndGet();
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
    }

    /** Analisa UMA execução (síncrono no worker — visível para teste/benchmark). */
    public List<Insight> analyzeExecution(Execution e) {
        FlowView flow = FlowView.of(e);
        history.record(flow);
        store.flowCriticality(e.executionId(), criticality(flow));
        List<LogEntry> logs = safeLogs(e);
        List<Execution> recent = safeCorpus();
        AnalysisContext ctx = new AnalysisContext(e, flow, logs, recent, history, project, port, cfg.settings());
        List<Insight> found = runAll(PredictiveAnalyzer.Scope.EXECUTION, ctx);
        // re-análise (continuação tardia): retira achados que a execução não sustenta mais
        java.util.Set<String> execAnalyzers = new java.util.HashSet<>();
        analyzers.stream().filter(a -> a.scope() == PredictiveAnalyzer.Scope.EXECUTION).forEach(a -> execAnalyzers.add(a.name()));
        java.util.Set<String> keep = new java.util.HashSet<>();
        found.forEach(i -> keep.add(i.fingerprint()));
        store.retractStale(e.executionId(), execAnalyzers, keep);
        store.accept(found);
        analyzed.incrementAndGet();
        corpusDirty = true;
        history.flush();
        return found;
    }

    /** Roda os analisadores de acervo agora (teste/benchmark e debounce do worker). */
    public List<Insight> analyzeCorpus() {
        AnalysisContext ctx = new AnalysisContext(null, null, List.of(), safeCorpus(), history, project, port, cfg.settings());
        List<Insight> found = runAll(PredictiveAnalyzer.Scope.CORPUS, ctx);
        store.accept(found);
        lastCorpusRun = System.currentTimeMillis();
        corpusDirty = false;
        return found;
    }

    /** Re-varre o projeto e roda os analisadores de projeto (teste/benchmark e worker). */
    public List<Insight> analyzeProject() {
        if (scanner != null) {
            project = scanner.scan();
        }
        AnalysisContext ctx = new AnalysisContext(null, null, List.of(), List.of(), history, project, port, cfg.settings());
        List<Insight> found = runAll(PredictiveAnalyzer.Scope.PROJECT, ctx);
        store.accept(found);
        return found;
    }

    private void maybeRunCorpus() {
        if (corpusDirty && System.currentTimeMillis() - lastCorpusRun >= cfg.corpusDebounceMs()) {
            analyzeCorpus();
        }
    }

    private void maybeRescanProject() {
        if (scanner == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastProjectCheck < cfg.projectRescanMs() && projectFingerprint != -1) {
            return;
        }
        lastProjectCheck = now;
        long fp = scanner.fingerprint();
        if (fp != projectFingerprint) {
            projectFingerprint = fp;
            analyzeProject();
        }
    }

    private List<Insight> runAll(PredictiveAnalyzer.Scope scope, AnalysisContext ctx) {
        List<Future<List<Insight>>> futures = new ArrayList<>();
        List<PredictiveAnalyzer> selected = analyzers.stream().filter(a -> a.scope() == scope).toList();
        for (PredictiveAnalyzer a : selected) {
            futures.add(workers.submit(() -> {
                permits.acquire();
                long t0 = System.nanoTime();
                try {
                    List<Insight> r = a.analyze(ctx);
                    return r == null ? List.<Insight>of() : r;
                } finally {
                    permits.release();
                    long[] st = perAnalyzer.computeIfAbsent(a.name(), k -> new long[3]);
                    synchronized (st) {
                        st[0]++;
                        st[1] += System.nanoTime() - t0;
                    }
                }
            }));
        }
        List<Insight> out = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            PredictiveAnalyzer a = selected.get(i);
            try {
                List<Insight> r = futures.get(i).get(cfg.analyzerTimeoutMs(), TimeUnit.MILLISECONDS);
                out.addAll(r);
                long[] st = perAnalyzer.get(a.name());
                if (st != null) {
                    synchronized (st) {
                        st[2] += r.size();
                    }
                }
            } catch (java.util.concurrent.TimeoutException te) {
                timeouts.incrementAndGet();
                futures.get(i).cancel(true);
                lastError = a.name() + ": timeout de " + cfg.analyzerTimeoutMs() + " ms";
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                failures.incrementAndGet();
                lastError = a.name() + ": " + (ex.getCause() != null ? ex.getCause() : ex);
            }
        }
        return out;
    }

    /** Criticidade do fluxo para o ranking: altera dados/assíncrono/erro pesa mais. */
    static double criticality(FlowView flow) {
        double c = 0.7;
        boolean writes = flow.steps().stream().anyMatch(s -> s.node().mutation() != null
                && s.node().mutation().kind() != null && !s.node().mutation().kind().name().equals("READ_ONLY"));
        if (writes) {
            c += 0.25;
        }
        if (flow.steps().stream().anyMatch(FlowView.Step::producer)) {
            c += 0.1;
        }
        if (flow.failed()) {
            c += 0.1;
        }
        return Math.min(1.2, c);
    }

    private List<LogEntry> safeLogs(Execution e) {
        try {
            return logsOf.apply(e);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<Execution> safeCorpus() {
        try {
            return corpus.get();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    public InsightStore store() {
        return store;
    }

    public FlowHistory history() {
        return history;
    }

    public ProjectSnapshot project() {
        return project;
    }

    /** Injeta uma fotografia de projeto já lida (cenários de benchmark, Station com projeto montado). */
    public void project(ProjectSnapshot snapshot) {
        this.project = snapshot == null ? ProjectSnapshot.EMPTY : snapshot;
        this.projectFingerprint = Long.MIN_VALUE + 1;
        this.lastProjectCheck = System.currentTimeMillis();
    }

    public DecisionEngine engine() {
        return engine;
    }

    public List<PredictiveAnalyzer> analyzers() {
        return analyzers;
    }

    /** Telemetria do pipeline (sem conteúdo). */
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", cfg.enabled());
        s.put("queueDepth", queue.size());
        s.put("queueCapacity", cfg.queueCapacity());
        s.put("submitted", submitted.get());
        s.put("dropped", dropped.get());
        s.put("analyzed", analyzed.get());
        s.put("timeouts", timeouts.get());
        s.put("failures", failures.get());
        s.put("submitMaxMicros", submitNanosMax.get() / 1000.0);
        s.put("lastError", lastError);
        s.put("historyFlows", history.flowCount());
        s.put("historySamples", history.sampleCount());
        s.put("historyFile", cfg.historyFile() != null ? cfg.historyFile().toString() : "desligado");
        s.put("insights", store.all().size());
        s.put("suppressed", store.suppressedCount());
        s.put("projectRoots", project.scannedRoots());
        s.put("projectScannedAt", project.scannedAt().toString());
        Map<String, Object> per = new LinkedHashMap<>();
        for (PredictiveAnalyzer a : analyzers) {
            long[] st = perAnalyzer.getOrDefault(a.name(), new long[3]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", a.scope().name());
            m.put("runs", st[0]);
            m.put("avgMicros", st[0] == 0 ? 0 : st[1] / st[0] / 1000);
            m.put("insights", st[2]);
            m.put("precision", store.precision(a.name()));
            per.put(a.name(), m);
        }
        s.put("analyzers", per);
        return s;
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        workers.shutdownNow();
        history.flush();
    }
}
