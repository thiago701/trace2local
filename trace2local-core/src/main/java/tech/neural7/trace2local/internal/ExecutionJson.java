package tech.neural7.trace2local.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.ErrorInfo;
import tech.neural7.trace2local.model.Execution;
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
import tech.neural7.trace2local.model.Warning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor do contrato JSON de {@link Execution} (o mesmo de {@code GET /api/executions/{id}}
 * e do {@code .tvtrace} exportado): {@code Instant} ISO-8601 e {@code Duration} em
 * milissegundos. Permite REPRODUZIR traces reais capturados (datasets de regressão das
 * Regras Preditivas, análise offline) sem depender do módulo jsr310 no core.
 *
 * <p>Tolerante: campo ausente vira {@code null}/padrão; enum desconhecido vira o
 * valor neutro ({@code UNKNOWN}, {@code EXTERNAL}…). Entrada malformada no nível da
 * execução lança {@link IllegalArgumentException}.
 */
public final class ExecutionJson {

    private static final int MAX_DEPTH = 256;

    private ExecutionJson() {}

    /** Aceita a execução crua ou o envelope {@code .tvtrace} ({@code {"manifest":…,"execution":…}}). */
    public static Execution fromJson(JsonNode root) {
        JsonNode n = root != null && root.has("execution") && root.path("execution").isObject() ? root.path("execution") : root;
        if (n == null || !n.isObject() || !n.hasNonNull("executionId")) {
            throw new IllegalArgumentException("JSON não é uma execução do Trace2Local (executionId ausente)");
        }
        List<Node> roots = new ArrayList<>();
        for (JsonNode r : n.path("roots")) {
            roots.add(node(r, 0));
        }
        JsonNode m = n.path("metrics");
        ExecutionMetrics metrics = m.isObject()
                ? new ExecutionMetrics(m.path("nodeCount").asInt(), m.path("maxDepth").asInt(), m.path("lostSpans").asInt(),
                m.path("droppedEvents").asLong())
                : ExecutionMetrics.EMPTY;
        List<Warning> warnings = new ArrayList<>();
        for (JsonNode w : n.path("warnings")) {
            warnings.add(new Warning(enumOf(Warning.WarningKind.class, text(w, "kind"), Warning.WarningKind.CONTEXT_LOST),
                    w.path("count").asInt(), text(w, "message"), instant(w, "at")));
        }
        return new Execution(text(n, "executionId"), text(n, "traceId"),
                enumOf(ExecutionStatus.class, text(n, "status"), ExecutionStatus.PARTIAL),
                enumOf(Trigger.class, text(n, "trigger"), Trigger.EXTERNAL),
                instant(n, "startedAt"), duration(n, "duration"), List.copyOf(roots), metrics, List.copyOf(warnings));
    }

    private static Node node(JsonNode n, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("árvore profunda demais (> " + MAX_DEPTH + ")");
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        n.path("attributes").fields().forEachRemaining(e -> attrs.put(e.getKey(), e.getValue().asText()));
        JsonNode p = n.path("payload");
        Payload payload = p.isObject() ? new Payload(text(p, "request"), text(p, "response")) : null;
        JsonNode er = n.path("error");
        ErrorInfo error = er.isObject() ? new ErrorInfo(text(er, "type"), text(er, "message"), text(er, "stack")) : null;
        List<Node> children = new ArrayList<>();
        for (JsonNode c : n.path("children")) {
            children.add(node(c, depth + 1));
        }
        return new Node(text(n, "nodeId"), text(n, "parentId"),
                enumOf(NodeKind.class, text(n, "kind"), NodeKind.UNKNOWN), text(n, "label"),
                enumOf(NodeStatus.class, text(n, "status"), NodeStatus.OK),
                instant(n, "startedAt"), duration(n, "selfTime"), duration(n, "totalTime"),
                Map.copyOf(attrs), payload, mutation(n.path("mutation")), error, List.copyOf(children));
    }

    private static DataMutation mutation(JsonNode m) {
        if (m == null || !m.isObject()) {
            return null;
        }
        List<FieldDelta> deltas = new ArrayList<>();
        for (JsonNode d : m.path("deltas")) {
            deltas.add(new FieldDelta(text(d, "path"), d.has("before") ? d.get("before") : NullNode.getInstance(),
                    d.has("after") ? d.get("after") : NullNode.getInstance()));
        }
        JsonNode before = m.get("before");
        JsonNode after = m.get("after");
        return new DataMutation(enumOf(MutationKind.class, text(m, "kind"), MutationKind.UPDATE), text(m, "target"), text(m, "key"),
                before == null || before.isNull() ? null : before, after == null || after.isNull() ? null : after,
                List.copyOf(deltas), enumOf(MutationFidelity.class, text(m, "fidelity"), MutationFidelity.values()[0]));
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Instant instant(JsonNode n, String f) {
        String v = text(n, f);
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Duration no fio: número em milissegundos (JsonCodec) ou ISO-8601 ({@code PT0.042S}). */
    private static Duration duration(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return Duration.ofNanos(Math.round(v.asDouble() * 1_000_000d));
        }
        try {
            return Duration.parse(v.asText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String v, E fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
