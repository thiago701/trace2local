package tech.neural7.trace2local.predictive.correlation;

import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;
import tech.neural7.trace2local.otel.OtelAttributeNames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Visão CORRELACIONADA de uma execução: a árvore achatada com tempos absolutos,
 * os SEGMENTOS do fluxo (síncrono, espera em fila, consumidor, banco, externo),
 * o caminho crítico e a assinatura estável do fluxo (base do histórico).
 *
 * <p>É aqui que "API 120 ms + SQS wait 3,8 s + Lambda 800 ms + DynamoDB 70 ms"
 * deixa de ser quatro spans soltos e vira UMA explicação com proporções.
 */
public final class FlowView {

    /** Um nó com tempos absolutos (ms desde o início da execução). */
    public record Step(Node node, String parentId, int depth, long startMs, long endMs, String component,
                       boolean producer, boolean asyncConsumer) {
        public long durationMs() {
            return Math.max(0, endMs - startMs);
        }

        public NodeKind kind() {
            return node.kind() != null ? node.kind() : NodeKind.UNKNOWN;
        }

        public boolean failed() {
            return node.status() == NodeStatus.ERROR;
        }
    }

    /** Segmento nomeado do fluxo (evidência de correlação). */
    public record Segment(String kind, String label, long startMs, long durationMs, String nodeId, String component) {}

    private final Execution execution;
    private final List<Step> steps = new ArrayList<>();
    private final List<Segment> segments = new ArrayList<>();
    private final Map<String, Step> byId = new LinkedHashMap<>();
    private long endToEndMs;

    private FlowView(Execution execution) {
        this.execution = execution;
    }

    public static FlowView of(Execution e) {
        FlowView v = new FlowView(e);
        Instant start = e.startedAt();
        for (Node root : e.roots() != null ? e.roots() : List.<Node>of()) {
            v.walk(root, null, 0, start, false);
        }
        v.endToEndMs = v.steps.stream().mapToLong(Step::endMs).max().orElse(
                e.duration() != null ? e.duration().toMillis() : 0);
        if (e.duration() != null) {
            v.endToEndMs = Math.max(v.endToEndMs, e.duration().toMillis());
        }
        v.buildSegments();
        return v;
    }

    private void walk(Node n, String parentId, int depth, Instant start, boolean underProducer) {
        long s = start != null && n.startedAt() != null ? Math.max(0, Duration.between(start, n.startedAt()).toMillis()) : 0;
        long d = n.totalTime() != null ? n.totalTime().toMillis() : 0;
        Map<String, String> attrs = n.attributes() != null ? n.attributes() : Map.of();
        NodeKind kind = n.kind() != null ? n.kind() : NodeKind.UNKNOWN;
        String op = attrs.getOrDefault(OtelAttributeNames.MESSAGING_OPERATION, "").toLowerCase(Locale.ROOT);
        boolean producer = (kind == NodeKind.SQS || kind == NodeKind.SNS) && !op.equals("receive") && !op.equals("process");
        boolean consumer = depth > 0 && underProducer && (kind == NodeKind.LAMBDA || kind == NodeKind.HTTP_SERVER
                || (kind == NodeKind.SQS && (op.equals("receive") || op.equals("process"))));
        Step step = new Step(n, parentId, depth, s, s + d, component(n), producer, consumer);
        steps.add(step);
        byId.put(n.nodeId(), step);
        for (Node c : n.children() != null ? n.children() : List.<Node>of()) {
            walk(c, n.nodeId(), depth + 1, start, underProducer || producer);
        }
    }

    /** Nome de componente estável (fila, tabela, função, rota) — chave da topologia. */
    public static String component(Node n) {
        Map<String, String> a = n.attributes() != null ? n.attributes() : Map.of();
        NodeKind kind = n.kind() != null ? n.kind() : NodeKind.UNKNOWN;
        String label = n.label() != null ? n.label() : "?";
        return switch (kind) {
            case DYNAMODB -> "dynamodb:" + a.getOrDefault(OtelAttributeNames.AWS_DYNAMO_TABLES, strip(label));
            case SQS -> "sqs:" + last(a.getOrDefault(OtelAttributeNames.MESSAGING_DESTINATION,
                    a.getOrDefault(OtelAttributeNames.AWS_SQS_QUEUE, strip(label))), '/');
            case SNS -> "sns:" + last(a.getOrDefault(OtelAttributeNames.AWS_SNS_TOPIC,
                    a.getOrDefault(OtelAttributeNames.MESSAGING_DESTINATION, strip(label))), ':');
            case LAMBDA -> "lambda:" + a.getOrDefault(OtelAttributeNames.FAAS_NAME, label);
            case SQL -> "sql:" + a.getOrDefault(OtelAttributeNames.DB_COLLECTION, strip(label));
            case HTTP_SERVER -> "http:" + (a.getOrDefault(OtelAttributeNames.HTTP_METHOD, "") + " "
                    + a.getOrDefault(OtelAttributeNames.HTTP_ROUTE, label)).trim();
            case HTTP_CLIENT -> "ext:" + a.getOrDefault(OtelAttributeNames.SERVER_ADDRESS, label);
            case BUSINESS -> "biz:" + label;
            case UNKNOWN -> "span:" + label;
        };
    }

    private static String strip(String label) {
        int i = label.indexOf(':');
        return i >= 0 ? label.substring(i + 1).trim() : label;
    }

    private static String last(String v, char sep) {
        int i = v.lastIndexOf(sep);
        return i >= 0 ? v.substring(i + 1) : v;
    }

    private void buildSegments() {
        if (steps.isEmpty()) {
            return;
        }
        Step root = steps.get(0);
        // 1) trecho síncrono da raiz (até o fim da raiz)
        segments.add(new Segment("sync", labelOf(root), root.startMs(), root.durationMs(), root.node().nodeId(), root.component()));
        // 2) espera em fila: fim do produtor → início do consumidor
        for (Step p : steps) {
            if (!p.producer()) {
                continue;
            }
            for (Step c : steps) {
                if (p.node().nodeId().equals(c.parentId()) && c.asyncConsumer()) {
                    long gap = Math.max(0, c.startMs() - p.endMs());
                    segments.add(new Segment("queue-wait", p.component().replaceFirst("^(sqs|sns):", "") + " → "
                            + c.component().replaceFirst("^(lambda|http):", ""), p.endMs(), gap, c.node().nodeId(), p.component()));
                    segments.add(new Segment("consumer", labelOf(c), c.startMs(), c.durationMs(), c.node().nodeId(), c.component()));
                }
            }
        }
        // 3) banco e chamadas externas (somadas por componente)
        Map<String, long[]> dbByComponent = new LinkedHashMap<>();
        for (Step s : steps) {
            if (s.kind() == NodeKind.DYNAMODB || s.kind() == NodeKind.SQL) {
                dbByComponent.computeIfAbsent(s.component(), k -> new long[] {s.startMs(), 0, 0});
                long[] acc = dbByComponent.get(s.component());
                acc[1] += s.durationMs();
                acc[2]++;
            } else if (s.kind() == NodeKind.HTTP_CLIENT) {
                segments.add(new Segment("external", labelOf(s), s.startMs(), s.durationMs(), s.node().nodeId(), s.component()));
            }
        }
        dbByComponent.forEach((comp, acc) -> segments.add(new Segment("db",
                comp.replaceFirst("^(dynamodb|sql):", "") + (acc[2] > 1 ? " ×" + acc[2] : ""),
                acc[0], acc[1], null, comp)));
    }

    private static String labelOf(Step s) {
        return s.node().label() != null ? s.node().label() : s.component();
    }

    /**
     * Assinatura do fluxo: raiz + resultado — fluxos de mesma entrada e mesmo
     * desfecho são comparáveis no histórico (o caminho de erro tem baseline próprio).
     */
    public String flowKey() {
        if (steps.isEmpty()) {
            return "vazio";
        }
        Step root = steps.get(0);
        boolean failed = execution.status() != null && execution.status().name().equals("FAILED");
        return root.component() + (failed ? " [falha]" : "");
    }

    /** Forma do fluxo (conjunto de componentes) — muda quando o código passa a tocar outra coisa. */
    public String shapeHash() {
        TreeSet<String> comps = new TreeSet<>();
        steps.forEach(s -> comps.add(s.component()));
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(String.join("|", comps).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(comps.hashCode());
        }
    }

    /** Caminho crítico: da raiz, sempre o filho que termina por último. */
    public List<Step> criticalPath() {
        List<Step> path = new ArrayList<>();
        if (steps.isEmpty()) {
            return path;
        }
        Step cur = steps.get(0);
        int guard = 0;
        while (cur != null && guard++ < 256) {
            path.add(cur);
            Step next = null;
            for (Step c : steps) {
                if (cur.node().nodeId().equals(c.parentId()) && (next == null || c.endMs() > next.endMs())) {
                    next = c;
                }
            }
            cur = next;
        }
        return path;
    }

    public long totalQueueWaitMs() {
        return segments.stream().filter(s -> s.kind().equals("queue-wait")).mapToLong(Segment::durationMs).sum();
    }

    public long totalDbMs() {
        return segments.stream().filter(s -> s.kind().equals("db")).mapToLong(Segment::durationMs).sum();
    }

    public int dbCalls() {
        return (int) steps.stream().filter(s -> s.kind() == NodeKind.DYNAMODB || s.kind() == NodeKind.SQL).count();
    }

    public Execution execution() {
        return execution;
    }

    public List<Step> steps() {
        return steps;
    }

    public List<Segment> segments() {
        return segments;
    }

    public Step step(String nodeId) {
        return byId.get(nodeId);
    }

    public long endToEndMs() {
        return endToEndMs;
    }

    public boolean failed() {
        return execution.status() != null && execution.status().name().equals("FAILED");
    }
}
