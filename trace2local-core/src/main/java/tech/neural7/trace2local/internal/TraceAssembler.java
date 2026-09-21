package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.ErrorInfo;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionMetrics;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.ExecutionSummary;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;
import tech.neural7.trace2local.model.Payload;
import tech.neural7.trace2local.model.Trigger;
import tech.neural7.trace2local.model.Warning;
import tech.neural7.trace2local.spi.MutationContext;
import tech.neural7.trace2local.spi.MutationEvent;
import tech.neural7.trace2local.spi.NodeBuilder;
import tech.neural7.trace2local.spi.SpanView;
import tech.neural7.trace2local.spi.Trace2LocalExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Monta o TVEM a partir de eventos que chegam FORA DE ORDEM (ADR-006, corolário 3).
 * Consome o ring buffer numa thread virtual dedicada; resolve parentesco por
 * spanId/parentSpanId, funde o Data Mutation Channel e emite os {@link LiveEvent}s.
 *
 * <p>Invariantes (SPEC §4.6): I1 — órfão é reparentado na raiz, nunca some;
 * I2 — selfTime nunca é negativo; I3 — fidelidade é declarada.
 */
public final class TraceAssembler implements AutoCloseable, ExecutionStore {

    private static final long SWEEP_INTERVAL_MS = 100;

    private final Trace2LocalRingBuffer buffer;
    private final Trace2LocalConfig cfg;
    private final CopyOnWriteArrayList<Consumer<LiveEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, ExecutionAcc> live = new ConcurrentHashMap<>();
    private final Map<String, Execution> completed;
    private final List<Trace2LocalExtension> extensions;

    private final Thread worker;
    private volatile boolean closed;
    private long lastDroppedSeen;
    private final java.util.concurrent.atomic.LongAdder internalErrors = new java.util.concurrent.atomic.LongAdder();

    public TraceAssembler(Trace2LocalRingBuffer buffer, Trace2LocalConfig cfg) {
        this.buffer = buffer;
        this.cfg = cfg;
        this.extensions = Extensions.all();
        this.completed = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Execution> eldest) {
                return size() > cfg.retentionMaxExecutions();
            }
        };
        this.worker = Thread.ofVirtual()
                .name("trace2local-assembler")
                .unstarted(this::run);
    }

    public void addListener(Consumer<LiveEvent> listener) {
        listeners.add(listener);
    }

    public void start() {
        worker.start();
    }

    // ---------------------------------------------------------------- loop

    private void run() {
        while (!closed) {
            try {
                Trace2LocalEvent event = buffer.poll(SWEEP_INTERVAL_MS);
                if (event != null) {
                    process(event);
                }
                sweep();
                reportDrops();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Degradação preferida à falha (SPEC §7.3): o assembler nunca derruba a app.
                // O contador de erros internos é visível em /api/health (ADR-006, consequência 2).
                internalErrors.increment();
            }
        }
    }

    private void process(Trace2LocalEvent event) {
        switch (event) {
            case SpanStartEvent s -> onSpanStart(s);
            case SpanEndEvent e -> onSpanEnd(e);
            case MutationEvent m -> onMutation(m);
            default -> { /* tipo desconhecido de evento — nunca deve ocorrer */ }
        }
    }

    // ---------------------------------------------------------------- events

    private void onSpanStart(SpanStartEvent s) {
        ExecutionAcc acc = accFor(s.traceId(), s.executionId(), s.trigger(), s.at());
        markStarted(acc);
        acc.sawStart = true;
        NodeAcc node = acc.nodes.get(s.spanId());
        if (node == null) {
            node = new NodeAcc(s.spanId(), s.kind(), s.label());
            node.startedAt = s.at();
            acc.nodes.put(s.spanId(), node);
            acc.openSpans.add(s.spanId());
            if (s.parentSpanId() != null) {
                NodeAcc parent = acc.nodes.get(s.parentSpanId());
                if (parent != null) {
                    attach(parent, node);
                } else {
                    acc.orphansByParent.computeIfAbsent(s.parentSpanId(), k -> new ArrayList<>()).add(node);
                }
            }
        }
        // filhos que chegaram ANTES deste pai (fora de ordem): anexam já no start dele
        attachOrphansWaitingFor(acc, node);
        node.attributes.putAll(s.attributes());
        emit(new LiveEvent.NodeUpserted(acc.executionId, freeze(node)));
    }

    private void markStarted(ExecutionAcc acc) {
        if (!acc.started) {
            acc.started = true;
            emit(new LiveEvent.ExecutionStarted(acc.executionId, acc.traceId, acc.trigger, acc.firstAt));
        }
    }

    private void onSpanEnd(SpanEndEvent e) {
        ExecutionAcc acc = accFor(e.traceId(), e.executionId(), e.trigger(), e.at());
        markStarted(acc);
        NodeAcc node = acc.nodes.get(e.spanId());
        if (node == null) {
            // span start perdido (descartado na borda) — o fim ainda monta o nó (I1)
            node = new NodeAcc(e.spanId(), e.kind(), e.label());
            acc.nodes.put(e.spanId(), node);
            if (!e.startDeliberatelyAbsent()) {
                acc.warnings.add(new Warning(Warning.WarningKind.EVENTS_DROPPED, 1,
                        "Início do span " + e.spanId() + " não foi observado; o nó foi montado a partir do fim.", e.at()));
            }
        }
        if (node.startedAt == null) {
            node.startedAt = e.startedAt() != null ? e.startedAt() : e.at();
        }
        // parentesco vindo no próprio evento de fim (ingest OTLP do Station):
        // anexa ao pai se ele já existe; senão aguarda em orphansByParent (I1)
        if (node.parentId == null && node.parent == null && e.parentSpanId() != null) {
            NodeAcc parent = acc.nodes.get(e.parentSpanId());
            if (parent != null) {
                attach(parent, node);
            } else {
                acc.orphansByParent.computeIfAbsent(e.parentSpanId(), k -> new ArrayList<>()).add(node);
            }
        }
        // o evento de fim é autoritativo, mas um UNKNOWN no fim não pode apagar a
        // semântica que o evento de início já tinha classificado
        if (e.kind() != null && e.kind() != NodeKind.UNKNOWN) {
            node.kind = e.kind();
        }
        if (e.label() != null) {
            node.label = e.label();
        }
        node.attributes.putAll(e.attributes());
        node.endedAt = e.at();
        node.status = e.error() ? NodeStatus.ERROR : NodeStatus.OK;
        node.error = e.errorInfo();
        node.linkedSpanIds = e.linkedSpanIds();
        if (e.payloadRequest() != null || e.payloadResponse() != null) {
            node.payload = new Payload(e.payloadRequest(), e.payloadResponse());
        }
        acc.openSpans.remove(e.spanId());

        DataMutation pending = acc.pendingMutations.remove(e.spanId());
        if (pending != null && node.mutation == null) {
            node.mutation = pending;
            emit(new LiveEvent.NodeMutation(acc.executionId, node.nodeId, pending));
        }

        resolveLinks(acc, node);
        attachOrphansWaitingFor(acc, node);
        runExtensions(acc, node);
        emit(new LiveEvent.NodeUpserted(acc.executionId, freeze(node)));
        maybeComplete(acc);
    }

    private void onMutation(MutationEvent m) {
        ExecutionAcc acc = accFor(m.traceId(), null, null, m.at());
        markStarted(acc);
        NodeAcc node = acc.nodes.get(m.spanId());
        if (node != null) {
            if (node.mutation == null) {
                node.mutation = m.mutation();
                emit(new LiveEvent.NodeMutation(acc.executionId, node.nodeId, m.mutation()));
            }
        } else {
            // mutação chegou antes do span (fora de ordem) — espera o fim do span
            acc.pendingMutations.put(m.spanId(), m.mutation());
        }
    }

    // ---------------------------------------------------------------- árvore

    private void attach(NodeAcc parent, NodeAcc child) {
        if (parent == child) {
            return;
        }
        // proteção contra ciclo: sobe a cadeia de pais do alvo procurando o filho
        for (NodeAcc cursor = parent; cursor != null; cursor = cursor.parent) {
            if (cursor == child) {
                return;
            }
        }
        if (child.parent != null) {
            child.parent.children.remove(child);
        }
        child.parent = parent;
        child.parentId = parent.nodeId;
        parent.children.add(child);
    }

    private void attachOrphansWaitingFor(ExecutionAcc acc, NodeAcc parent) {
        List<NodeAcc> waiting = acc.orphansByParent.remove(parent.nodeId);
        if (waiting != null) {
            for (NodeAcc orphan : waiting) {
                attach(parent, orphan);
            }
        }
    }

    /** Correlação assíncrona via Link (SPEC §4.11): consumidor é reparentado sob o produtor ligado. */
    private void resolveLinks(ExecutionAcc acc, NodeAcc node) {
        if (node.linkedSpanIds == null || node.linkedSpanIds.isEmpty()) {
            return;
        }
        for (String linkedId : node.linkedSpanIds) {
            NodeAcc target = acc.nodes.get(linkedId);
            if (target == null || target == node) {
                continue;
            }
            if (node.parentId == null || node.status == NodeStatus.ORPHANED) {
                attach(target, node);
                break;
            }
        }
    }

    private void runExtensions(ExecutionAcc acc, NodeAcc node) {
        if (extensions.isEmpty()) {
            return;
        }
        SpanView view = new SpanView(node.nodeId, acc.traceId, node.parentId,
                node.label, node.kind, node.label, node.status, node.startedAt,
                node.endedAt != null ? Duration.between(node.startedAt, node.endedAt) : null,
                Map.copyOf(node.attributes), node.status == NodeStatus.ERROR);
        NodeBuilder builder = new NodeBuilder(node);
        for (Trace2LocalExtension extension : extensions) {
            try {
                extension.contribute(builder, view);
            } catch (Throwable ignored) {
                // extensão quebrada nunca derruba a app (SPEC §7.3)
            }
        }
        if (node.mutation == null) {
            MutationContext ctx = new MutationContext(node.nodeId, acc.traceId, node.kind, node.label, node.attributes);
            for (Trace2LocalExtension extension : extensions) {
                try {
                    var mutation = extension.captureMutation(ctx);
                    if (mutation.isPresent()) {
                        node.mutation = mutation.get();
                        emit(new LiveEvent.NodeMutation(acc.executionId, node.nodeId, node.mutation));
                        break;
                    }
                } catch (Throwable ignored) {
                    // idem
                }
            }
        }
    }

    // ---------------------------------------------------------------- completude

    private void maybeComplete(ExecutionAcc acc) {
        // só completa na hora quando houve spans abertos de verdade (fluxo clássico)
        // E não há produtor SNS/SQS aguardando consumidor: nesse caso a execução
        // espera a janela de quiescência para o consumidor ligado via Link chegar
        // e ser reparentado sob o produtor (SPEC §4.11/JC-3/E8).
        if (!acc.completed && acc.started && acc.openSpans.isEmpty()
                && acc.sawStart && !hasProducerAwaitingConsumer(acc)) {
            complete(acc);
        }
    }

    private boolean hasProducerAwaitingConsumer(ExecutionAcc acc) {
        for (NodeAcc node : acc.nodes.values()) {
            if (node.kind == NodeKind.SNS && node.children.isEmpty() && node.endedAt != null) {
                return true;
            }
        }
        return false;
    }

    private void sweep() {
        long nowNanos = System.nanoTime();
        for (ExecutionAcc acc : live.values()) {
            if (acc.completed || !acc.started) {
                continue;
            }
            // Execução que só viu mutações (sem span ainda): não completa com zero nós —
            // espera a janela para descartar, caso o span nunca chegue (ADR-003).
            if (acc.nodes.isEmpty()) {
                if ((nowNanos - acc.lastProcessedNanos) > cfg.quiescenceMs() * 1_000_000L) {
                    live.remove(acc.traceId, acc);
                }
                continue;
            }
            if (acc.openSpans.isEmpty()) {
                // viu início de span e não há produtor aguardando? completa já.
                // Só chegou span fechado (OTLP) OU há produtor SNS esperando o
                // consumidor ligado por Link? espera a janela de quiescência.
                if ((acc.sawStart && !hasProducerAwaitingConsumer(acc))
                        || (nowNanos - acc.lastProcessedNanos) > cfg.quiescenceMs() * 1_000_000L) {
                    complete(acc);
                }
                continue;
            }
            // Quiescência medida pelo TEMPO DE PROCESSAMENTO (não pelos timestamps dos
            // eventos — no Station, relógios de serviços distintos podem divergir):
            // spans abertos além da janela ⇒ congelar como PARTIAL com aviso honesto.
            if ((nowNanos - acc.lastProcessedNanos) > cfg.quiescenceMs() * 1_000_000L) {
                acc.warnings.add(new Warning(Warning.WarningKind.LOST_SPANS, acc.openSpans.size(),
                        "Spans ficaram abertos além da janela de quiescência; a árvore foi congelada incompleta.", Instant.now()));
                acc.lostSpans = acc.openSpans.size();
                complete(acc);
            }
        }
    }

    private void reportDrops() {
        long dropped = buffer.dropped();
        if (dropped > lastDroppedSeen) {
            long delta = dropped - lastDroppedSeen;
            lastDroppedSeen = dropped;
            emit(new LiveEvent.SystemWarning(new Warning(Warning.WarningKind.EVENTS_DROPPED, (int) delta,
                    "Buffer cheio; " + delta + " evento(s) descartado(s) (total: " + dropped + "). A árvore pode estar incompleta.",
                    Instant.now())));
        }
    }

    private void complete(ExecutionAcc acc) {
        if (acc.completed) {
            return;
        }
        acc.completed = true;
        Instant now = Instant.now();

        // execução sem nenhum nó (ex.: só eventos de mutação órfãos) não entra no acervo
        if (acc.nodes.isEmpty()) {
            live.remove(acc.traceId, acc);
            return;
        }

        // I1 — órfãos cujo pai nunca chegou são reparentados na raiz, nunca descartados
        int orphanCount = 0;
        for (List<NodeAcc> waiting : acc.orphansByParent.values()) {
            for (NodeAcc orphan : waiting) {
                orphan.parentId = null;
                orphan.status = NodeStatus.ORPHANED;
                orphanCount++;
            }
        }
        if (orphanCount > 0) {
            acc.warnings.add(new Warning(Warning.WarningKind.ORPHANED_NODES, orphanCount,
                    orphanCount + " nó(s) reparentado(s) na raiz: pai não observado (contexto possivelmente perdido em thread não instrumentada).", now));
            acc.warnings.add(new Warning(Warning.WarningKind.CONTEXT_LOST, orphanCount,
                    "Contexto possivelmente perdido em thread não instrumentada.", now));
        }
        acc.orphansByParent.clear();

        // JC-3 — produtores SNS/SQS sem consumidor observado até o fim
        int awaitingConsumption = markProducersAwaitingConsumption(acc);
        if (awaitingConsumption > 0) {
            acc.warnings.add(new Warning(Warning.WarningKind.ORPHANED_NODES, awaitingConsumption,
                    awaitingConsumption + " ramo(s) SNS/SQS sem consumidor observado nesta execução — órfão aguardando consumo.", now));
        }

        List<Node> roots = new ArrayList<>();
        for (NodeAcc node : acc.nodes.values()) {
            if (node.parentId == null) {
                roots.add(freeze(node));
            }
        }
        roots.sort(Comparator.comparing(n -> n.startedAt() != null ? n.startedAt() : Instant.EPOCH));

        boolean anyError = false;
        for (NodeAcc node : acc.nodes.values()) {
            if (node.status == NodeStatus.ERROR) {
                anyError = true;
                break;
            }
        }

        ExecutionStatus status;
        if (roots.isEmpty() && !acc.nodes.isEmpty()) {
            status = ExecutionStatus.ORPHANED;
        } else if (anyError) {
            status = ExecutionStatus.FAILED;
        } else if (!acc.warnings.isEmpty() || acc.lostSpans > 0) {
            status = ExecutionStatus.PARTIAL;
        } else {
            status = ExecutionStatus.COMPLETED;
        }

        // Duração = JANELA DOS SPANS (min início → max fim), não a ordem de
        // processamento dos eventos: mutações chegam depois dos spans mas são
        // capturadas DURANTE eles — medir pelo último evento processado dava
        // duração NEGATIVA (ex.: -255 ms no modo Lambda). Piso em zero para
        // relógios divergentes entre serviços (mesma regra I2 da SPEC §4.6).
        Instant spanStart = null;
        Instant spanEnd = null;
        for (NodeAcc node : acc.nodes.values()) {
            if (node.startedAt != null && (spanStart == null || node.startedAt.isBefore(spanStart))) {
                spanStart = node.startedAt;
            }
            if (node.endedAt != null && (spanEnd == null || node.endedAt.isAfter(spanEnd))) {
                spanEnd = node.endedAt;
            }
        }
        Duration duration;
        if (spanStart != null && spanEnd != null) {
            Duration between = Duration.between(spanStart, spanEnd);
            duration = between.isNegative() ? Duration.ZERO : between;
        } else if (acc.firstAt != null && acc.lastAt != null) {
            Duration between = Duration.between(acc.firstAt, acc.lastAt);
            duration = between.isNegative() ? Duration.ZERO : between;
        } else {
            duration = Duration.ZERO;
        }

        Execution execution = new Execution(
                acc.executionId,
                acc.traceId,
                status,
                acc.trigger,
                acc.firstAt,
                duration,
                List.copyOf(roots),
                new ExecutionMetrics(acc.nodes.size(), maxDepth(roots), acc.lostSpans, buffer.dropped()),
                List.copyOf(acc.warnings));

        synchronized (completed) {
            completed.put(acc.executionId, execution);
        }
        live.remove(acc.traceId, acc);
        emit(new LiveEvent.ExecutionCompleted(acc.executionId, execution));
    }

    private int markProducersAwaitingConsumption(ExecutionAcc acc) {
        int count = 0;
        for (NodeAcc node : acc.nodes.values()) {
            // só PRODUTOR: SNS sempre publica; SQS é ambíguo (receive tem Link) e não é marcado
            if (node.kind == NodeKind.SNS && node.children.isEmpty() && node.status == NodeStatus.OK) {
                node.status = NodeStatus.ORPHANED;
                count++;
            }
        }
        return count;
    }

    private static int maxDepth(List<Node> roots) {
        int max = 0;
        for (Node root : roots) {
            max = Math.max(max, depth(root, 1));
        }
        return max;
    }

    private static int depth(Node node, int current) {
        int max = current;
        for (Node child : node.children()) {
            max = Math.max(max, depth(child, current + 1));
        }
        return max;
    }

    // ---------------------------------------------------------------- freeze

    private static Node freeze(NodeAcc acc) {
        List<Node> children = new ArrayList<>(acc.children.size());
        long childrenTotalNanos = 0;
        for (NodeAcc child : acc.children) {
            Node frozen = freeze(child);
            children.add(frozen);
            // I2 — selfTime subtrai apenas os filhos SOBREPOSTOS à janela do pai
            // (SPEC §4.6: "totalTime dos filhos sobrepostos", não todos)
            if (frozen.totalTime() != null && acc.startedAt != null && acc.endedAt != null) {
                Instant childStart = child.startedAt != null ? child.startedAt : acc.startedAt;
                Instant childEnd = child.endedAt != null ? child.endedAt : childStart;
                Instant overlapStart = childStart.isBefore(acc.startedAt) ? acc.startedAt : childStart;
                Instant overlapEnd = childEnd.isAfter(acc.endedAt) ? acc.endedAt : childEnd;
                if (overlapEnd.isAfter(overlapStart)) {
                    childrenTotalNanos += Duration.between(overlapStart, overlapEnd).toNanos();
                }
            }
        }
        Duration total = acc.endedAt != null && acc.startedAt != null
                ? Duration.between(acc.startedAt, acc.endedAt)
                : null;
        Duration self;
        if (total != null) {
            Duration childrenSum = Duration.ofNanos(childrenTotalNanos);
            self = total.minus(childrenSum);
            if (self.isNegative()) {
                self = Duration.ZERO; // I2 — piso em zero, sempre
            }
        } else {
            self = Duration.ZERO; // nó ainda aberto (congelado por quiescência)
        }
        return new Node(acc.nodeId, acc.parentId, acc.kind, acc.label, acc.status,
                acc.startedAt, self, total,
                Map.copyOf(acc.attributes), acc.payload, acc.mutation, acc.error,
                List.copyOf(children));
    }

    // ---------------------------------------------------------------- acc

    private ExecutionAcc accFor(String traceId, String executionId, Trigger trigger, Instant at) {
        return live.compute(traceId, (k, existing) -> {
            ExecutionAcc acc = existing;
            if (acc == null) {
                acc = new ExecutionAcc();
                acc.traceId = traceId;
                acc.executionId = executionId != null ? executionId : ExecutionIds.next();
                acc.executionIdGenerated = executionId == null;
                acc.trigger = trigger != null ? trigger : Trigger.EXTERNAL;
                acc.firstAt = at;
            }
            // identidade ESTÁVEL: o primeiro id explícito (span raiz) vence — um span
            // tardio com outro t2l.execution.id (ex.: consumidor SQS continuando o
            // trace) não pode renomear a execução no meio do caminho
            if (executionId != null && acc.executionIdGenerated) {
                acc.executionId = executionId;
                acc.executionIdGenerated = false;
            }
            if (trigger != null) {
                acc.trigger = trigger;
            }
            if (at != null) {
                acc.lastAt = at;
                if (acc.firstAt == null) {
                    acc.firstAt = at;
                }
            }
            acc.lastProcessedNanos = System.nanoTime();
            return acc;
        });
    }

    private void emit(LiveEvent event) {
        for (Consumer<LiveEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Throwable ignored) {
                // listener (SSE hub) quebrado não afeta o pipeline
            }
        }
    }

    // ---------------------------------------------------------------- store

    @Override
    public List<ExecutionSummary> recent(int limit) {
        synchronized (completed) {
            List<Execution> all = new ArrayList<>(completed.values());
            all.sort(Comparator.comparing(Execution::startedAt).reversed());
            int n = Math.min(limit, all.size());
            List<ExecutionSummary> summaries = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Execution e = all.get(i);
                String rootLabel = e.roots().isEmpty() ? null : e.roots().get(0).label();
                summaries.add(new ExecutionSummary(e.executionId(), e.traceId(), e.status(), e.trigger(),
                        e.startedAt(), e.duration(), rootLabel, e.metrics().nodeCount()));
            }
            return List.copyOf(summaries);
        }
    }

    @Override
    public java.util.Optional<Execution> get(String executionId) {
        synchronized (completed) {
            return java.util.Optional.ofNullable(completed.get(executionId));
        }
    }

    @Override
    public void clear() {
        synchronized (completed) {
            completed.clear();
        }
        live.clear();
    }

    @Override
    public long droppedEvents() {
        return buffer.dropped();
    }

    @Override
    public long internalErrors() {
        return internalErrors.sum();
    }

    @Override
    public int bufferSize() {
        return buffer.size();
    }

    @Override
    public int liveExecutions() {
        return live.size();
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
    }

    // ---------------------------------------------------------------- estado mutável

    static final class NodeAcc implements NodeBuilder.MutableNode {
        final String nodeId;
        NodeKind kind;
        String label;
        NodeStatus status = NodeStatus.PENDING;
        Instant startedAt;
        Instant endedAt;
        String parentId;
        NodeAcc parent;
        final Map<String, String> attributes = new LinkedHashMap<>();
        Payload payload;
        DataMutation mutation;
        ErrorInfo error;
        List<String> linkedSpanIds = List.of();
        final List<NodeAcc> children = new ArrayList<>();

        NodeAcc(String nodeId, NodeKind kind, String label) {
            this.nodeId = nodeId;
            this.kind = kind != null ? kind : NodeKind.UNKNOWN;
            this.label = label != null ? label : "?";
        }

        @Override
        public void setLabel(String label) {
            this.label = label;
        }

        @Override
        public void addAttribute(String key, String value) {
            attributes.put(key, value);
        }

        @Override
        public void setPayload(Payload payload) {
            this.payload = payload;
        }

        @Override
        public void setMutation(DataMutation mutation) {
            this.mutation = mutation;
        }

        @Override
        public void setError(ErrorInfo errorInfo) {
            this.error = errorInfo;
        }

        @Override
        public void setStatus(NodeStatus status) {
            this.status = status;
        }
    }

    static final class ExecutionAcc {
        String executionId;
        boolean executionIdGenerated;
        String traceId;
        Trigger trigger = Trigger.EXTERNAL;
        Instant firstAt;
        Instant lastAt;
        long lastProcessedNanos;
        final Map<String, NodeAcc> nodes = new HashMap<>();
        final Set<String> openSpans = new HashSet<>();
        final Map<String, List<NodeAcc>> orphansByParent = new HashMap<>();
        final Map<String, DataMutation> pendingMutations = new HashMap<>();
        final List<Warning> warnings = new ArrayList<>();
        boolean started;
        boolean completed;
        boolean sawStart;
        int lostSpans;
    }
}
