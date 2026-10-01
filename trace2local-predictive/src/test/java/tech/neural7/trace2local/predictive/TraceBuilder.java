package tech.neural7.trace2local.predictive;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.ErrorInfo;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.otel.OtelAttributeNames;
import tech.neural7.trace2local.model.ExecutionMetrics;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.FieldDelta;
import tech.neural7.trace2local.model.MutationFidelity;
import tech.neural7.trace2local.model.MutationKind;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;
import tech.neural7.trace2local.model.Payload;
import tech.neural7.trace2local.model.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSL de cenários para o dataset de validação (ADR-013 §14): monta execuções
 * TVEM realistas (tempos absolutos, atributos OTel, deltas, erros) sem depender
 * de LocalStack — reprodutível bit a bit no CI.
 */
public final class TraceBuilder {

    private final String executionId;
    private final Instant base;
    private final List<Spec> specs = new ArrayList<>();
    private ExecutionStatus status = ExecutionStatus.COMPLETED;
    private Trigger trigger = Trigger.EXTERNAL;
    private int seq;

    private TraceBuilder(String executionId, Instant base) {
        this.executionId = executionId;
        this.base = base;
    }

    public static TraceBuilder execution(String id, Instant base) {
        return new TraceBuilder(id, base);
    }

    public TraceBuilder status(ExecutionStatus s) {
        this.status = s;
        return this;
    }

    public TraceBuilder trigger(Trigger t) {
        this.trigger = t;
        return this;
    }

    /** Especificação de um nó (tempos em ms relativos ao início da execução). */
    public final class Spec {
        final String id;
        final String parentId;
        final NodeKind kind;
        final String label;
        final long start;
        final long duration;
        final Map<String, String> attrs = new LinkedHashMap<>();
        NodeStatus status = NodeStatus.OK;
        DataMutation mutation;
        ErrorInfo error;
        Payload payload;

        Spec(String parentId, NodeKind kind, String label, long start, long duration) {
            this.id = executionId + "-n" + (++seq);
            this.parentId = parentId;
            this.kind = kind;
            this.label = label;
            this.start = start;
            this.duration = duration;
        }

        public Spec attr(String k, String v) {
            attrs.put(k, v);
            return this;
        }

        public Spec child(NodeKind kind, String label, long start, long duration) {
            Spec s = new Spec(id, kind, label, start, duration);
            specs.add(s);
            return s;
        }

        public Spec error(String type, String message) {
            this.status = NodeStatus.ERROR;
            this.error = new ErrorInfo(type, message, null);
            return this;
        }

        public Spec mutation(MutationKind kind, String table, String key, String beforeJson, String afterJson,
                             String... deltas) {
            List<FieldDelta> d = new ArrayList<>();
            for (int i = 0; i + 2 < deltas.length; i += 3) {
                d.add(new FieldDelta(deltas[i], json(deltas[i + 1]), json(deltas[i + 2])));
            }
            this.mutation = new DataMutation(kind, table, key, json(beforeJson), json(afterJson), d, MutationFidelity.EXACT);
            return this;
        }

        /** Δ inferido do SQL (JDBC sem valores): chave é o WHERE com placeholders, nunca a entidade. */
        public Spec inferredMutation(MutationKind kind, String table, String whereKey) {
            this.mutation = new DataMutation(kind, table, whereKey, null, null, List.of(), MutationFidelity.INFERRED);
            return this;
        }

        public Spec payload(String request, String response) {
            this.payload = new Payload(request, response);
            return this;
        }

        public String id() {
            return id;
        }
    }

    public Spec root(NodeKind kind, String label, long start, long duration) {
        Spec s = new Spec(null, kind, label, start, duration);
        specs.add(s);
        return s;
    }

    // ------------------------------------------------------------------ atalhos semânticos

    public static Spec http(Spec parentOrNull, TraceBuilder t, String method, String route, long start, long dur) {
        Spec s = parentOrNull == null ? t.root(NodeKind.HTTP_SERVER, method + " " + route, start, dur)
                : parentOrNull.child(NodeKind.HTTP_SERVER, method + " " + route, start, dur);
        return s.attr(OtelAttributeNames.HTTP_METHOD, method).attr(OtelAttributeNames.HTTP_ROUTE, route).attr(OtelAttributeNames.HTTP_STATUS, "201");
    }

    public static Spec dynamo(Spec parent, String op, String table, long start, long dur) {
        return parent.child(NodeKind.DYNAMODB, "DynamoDB: " + table, start, dur)
                .attr(OtelAttributeNames.RPC_METHOD, op).attr(OtelAttributeNames.AWS_DYNAMO_TABLES, table);
    }

    public static Spec sqsPublish(Spec parent, String queue, long start, long dur) {
        return parent.child(NodeKind.SQS, "SQS: " + queue, start, dur)
                .attr(OtelAttributeNames.MESSAGING_SYSTEM, "aws_sqs").attr(OtelAttributeNames.MESSAGING_DESTINATION, queue)
                .attr(OtelAttributeNames.MESSAGING_OPERATION, "publish");
    }

    public static Spec lambda(Spec parent, String fn, String requestId, long start, long dur) {
        return parent.child(NodeKind.LAMBDA, fn, start, dur)
                .attr(OtelAttributeNames.FAAS_NAME, fn).attr(OtelAttributeNames.FAAS_INVOCATION_ID, requestId);
    }

    // ------------------------------------------------------------------ build

    public Execution build() {
        Map<String, List<Spec>> byParent = new LinkedHashMap<>();
        for (Spec s : specs) {
            byParent.computeIfAbsent(s.parentId, k -> new ArrayList<>()).add(s);
        }
        List<Node> roots = new ArrayList<>();
        for (Spec r : byParent.getOrDefault(null, List.of())) {
            roots.add(node(r, byParent));
        }
        long end = specs.stream().mapToLong(s -> s.start + s.duration).max().orElse(0);
        int depth = maxDepth(roots, 1);
        return new Execution(executionId, (executionId + "00000000000000000000000000000000").substring(0, 32)
                .replaceAll("[^0-9a-f]", "a"), status, trigger, base, Duration.ofMillis(end), roots,
                new ExecutionMetrics(specs.size(), depth, 0, 0), List.of());
    }

    private Node node(Spec s, Map<String, List<Spec>> byParent) {
        List<Node> children = new ArrayList<>();
        long childTime = 0;
        for (Spec c : byParent.getOrDefault(s.id, List.of())) {
            children.add(node(c, byParent));
            childTime += c.duration;
        }
        long self = Math.max(0, s.duration - childTime);
        return new Node(s.id, s.parentId, s.kind, s.label, s.status, base.plusMillis(s.start),
                Duration.ofMillis(self), Duration.ofMillis(s.duration), Map.copyOf(s.attrs), s.payload, s.mutation,
                s.error, children);
    }

    private static int maxDepth(List<Node> nodes, int d) {
        int m = nodes.isEmpty() ? d - 1 : d;
        for (Node n : nodes) {
            m = Math.max(m, maxDepth(n.children(), d + 1));
        }
        return m;
    }

    private static JsonNode json(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return JsonSupport.MAPPER.readTree(raw);
        } catch (Exception e) {
            return JsonSupport.MAPPER.getNodeFactory().textNode(raw);
        }
    }
}
