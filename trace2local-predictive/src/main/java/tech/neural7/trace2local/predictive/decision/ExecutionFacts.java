package tech.neural7.trace2local.predictive.decision;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.FieldDelta;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;
import tech.neural7.trace2local.otel.OtelAttributeNames;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FATOS de uma execução — a camada que nenhum modelo pode contradizer (status,
 * tipos, erros, deltas, durações). É a matéria-prima do modelo determinístico,
 * do estado minimizado enviado ao Jev e da fusão "fato primeiro".
 */
public record ExecutionFacts(
        String executionId,
        String traceId,
        String status,
        String trigger,
        long durationMs,
        List<NodeFact> nodes,
        List<RuleFact> rules,
        List<LogFact> logs,
        int warnings) {

    /** Um nó, com o que importa para decidir. */
    public record NodeFact(
            String nodeId,
            String parentId,
            int order,
            int depth,
            NodeKind kind,
            String label,
            String operation,
            String resource,
            NodeStatus status,
            long totalMs,
            long selfMs,
            long startOffsetMs,
            String mutationKind,
            String mutationTarget,
            String mutationKey,
            String mutationFidelity,
            List<String> changedFields,
            Map<String, String[]> deltaValues,
            String errorType,
            String errorMessage,
            boolean asyncConsumer,
            boolean producer) {

        public boolean failed() {
            return status == NodeStatus.ERROR;
        }

        /** Texto de busca (rótulo + operação + recurso + erro) — fold aplicado pelo chamador. */
        public String searchable() {
            return String.join(" ", nz(label), nz(operation), nz(resource), nz(errorType), nz(errorMessage),
                    nz(mutationTarget), String.join(" ", changedFields));
        }
    }

    /** Uma regra/termo do glossário de negócio com os nós que a exercitam. */
    public record RuleFact(String id, String term, String text, List<String> nodeIds, boolean explicitRule) {}

    /** Uma linha de log relevante. */
    public record LogFact(int index, String level, String message, String spanId, boolean platform, long offsetMs) {}

    // ------------------------------------------------------------------ montagem

    /** Monta os fatos a partir da execução, dos logs e do glossário (termo → descrição). */
    public static ExecutionFacts of(Execution e, List<LogEntry> logs, Map<String, String> glossary) {
        List<NodeFact> nodes = new ArrayList<>();
        Instant start = e.startedAt();
        int[] order = {1};
        for (Node root : e.roots() != null ? e.roots() : List.<Node>of()) {
            walk(root, null, 0, start, false, nodes, order);
        }
        List<RuleFact> rules = rules(glossary, nodes);
        List<LogFact> logFacts = new ArrayList<>();
        if (logs != null) {
            int i = 0;
            for (LogEntry l : logs) {
                long offset = start != null && l.timestamp() != null ? Duration.between(start, l.timestamp()).toMillis() : 0;
                logFacts.add(new LogFact(i++, l.level(), l.message(), l.spanId(), l.isPlatformLine(), offset));
            }
        }
        return new ExecutionFacts(e.executionId(), e.traceId(),
                e.status() != null ? e.status().name() : "RUNNING",
                e.trigger() != null ? e.trigger().name() : "EXTERNAL",
                e.duration() != null ? e.duration().toMillis() : 0,
                nodes, rules, logFacts, e.warnings() != null ? e.warnings().size() : 0);
    }

    private static void walk(Node n, String parentId, int depth, Instant start, boolean underProducer,
                             List<NodeFact> out, int[] order) {
        Map<String, String> attrs = n.attributes() != null ? n.attributes() : Map.of();
        NodeKind kind = n.kind() != null ? n.kind() : NodeKind.UNKNOWN;
        boolean producer = (kind == NodeKind.SQS || kind == NodeKind.SNS)
                && !"receive".equalsIgnoreCase(attrs.getOrDefault(OtelAttributeNames.MESSAGING_OPERATION, ""))
                && !"process".equalsIgnoreCase(attrs.getOrDefault(OtelAttributeNames.MESSAGING_OPERATION, ""));
        boolean consumer = depth > 0 && (kind == NodeKind.LAMBDA || kind == NodeKind.HTTP_SERVER) && underProducer;
        DataMutation m = n.mutation();
        List<String> changed = new ArrayList<>();
        Map<String, String[]> values = new LinkedHashMap<>();
        if (m != null && m.deltas() != null) {
            for (FieldDelta d : m.deltas()) {
                changed.add(d.path());
                values.put(d.path(), new String[] {text(d.before()), text(d.after())});
            }
        }
        long offset = start != null && n.startedAt() != null ? Duration.between(start, n.startedAt()).toMillis() : 0;
        out.add(new NodeFact(
                n.nodeId(), parentId, order[0]++, depth, kind, n.label(),
                operation(kind, attrs), resource(kind, attrs, n.label()),
                n.status() != null ? n.status() : NodeStatus.OK,
                n.totalTime() != null ? n.totalTime().toMillis() : 0,
                n.selfTime() != null ? n.selfTime().toMillis() : 0,
                Math.max(0, offset),
                m != null && m.kind() != null ? m.kind().name() : null,
                m != null ? m.target() : null,
                m != null ? m.key() : null,
                m != null && m.fidelity() != null ? m.fidelity().name() : null,
                changed, values,
                n.error() != null ? simpleType(n.error().type()) : null,
                n.error() != null ? n.error().message() : null,
                consumer, producer));
        for (Node child : n.children() != null ? n.children() : List.<Node>of()) {
            walk(child, n.nodeId(), depth + 1, start, underProducer || producer, out, order);
        }
    }

    static String operation(NodeKind kind, Map<String, String> attrs) {
        String op = attrs.getOrDefault(OtelAttributeNames.RPC_METHOD, "");
        if (op.isBlank()) {
            op = attrs.getOrDefault(OtelAttributeNames.DB_OPERATION, "");
        }
        if (op.isBlank()) {
            op = attrs.getOrDefault(OtelAttributeNames.MESSAGING_OPERATION, "");
        }
        if (op.isBlank() && (kind == NodeKind.HTTP_SERVER || kind == NodeKind.HTTP_CLIENT)) {
            op = (attrs.getOrDefault(OtelAttributeNames.HTTP_METHOD, "") + " "
                    + attrs.getOrDefault(OtelAttributeNames.HTTP_ROUTE, "")).trim();
        }
        return op;
    }

    static String resource(NodeKind kind, Map<String, String> attrs, String label) {
        String r = switch (kind) {
            case DYNAMODB -> attrs.getOrDefault(OtelAttributeNames.AWS_DYNAMO_TABLES, "");
            case SQS -> lastSegment(attrs.getOrDefault(OtelAttributeNames.MESSAGING_DESTINATION,
                    attrs.getOrDefault(OtelAttributeNames.AWS_SQS_QUEUE, "")), '/');
            case SNS -> lastSegment(attrs.getOrDefault(OtelAttributeNames.AWS_SNS_TOPIC,
                    attrs.getOrDefault(OtelAttributeNames.MESSAGING_DESTINATION, "")), ':');
            case LAMBDA -> attrs.getOrDefault(OtelAttributeNames.FAAS_NAME, "");
            case SQL -> attrs.getOrDefault(OtelAttributeNames.DB_COLLECTION, "");
            case HTTP_SERVER -> attrs.getOrDefault(OtelAttributeNames.HTTP_ROUTE, "");
            case HTTP_CLIENT -> attrs.getOrDefault(OtelAttributeNames.SERVER_ADDRESS, "");
            default -> "";
        };
        return r == null || r.isBlank() ? (label != null ? label : "?") : r;
    }

    private static String lastSegment(String v, char sep) {
        if (v == null) {
            return "";
        }
        int i = v.lastIndexOf(sep);
        return i >= 0 ? v.substring(i + 1) : v;
    }

    private static String simpleType(String type) {
        if (type == null) {
            return null;
        }
        int i = type.lastIndexOf('.');
        return i >= 0 ? type.substring(i + 1) : type;
    }

    private static String text(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isObject() && v.size() == 1) {
            // atributo DynamoDB: {"S":"PENDING"} → PENDING
            JsonNode inner = v.elements().next();
            if (inner.isValueNode()) {
                return inner.asText();
            }
        }
        return v.isValueNode() ? v.asText() : v.toString();
    }

    /**
     * Regras: cada termo do glossário vira uma regra quando a descrição tem
     * linguagem normativa ("regra", "só", "deve", "não pode", "condição", "uma vez")
     * ou é referência de funcionalidade (termo casa um nó); termos que só
     * descrevem tabelas/filas ficam como contexto.
     */
    static List<RuleFact> rules(Map<String, String> glossary, List<NodeFact> nodes) {
        List<RuleFact> out = new ArrayList<>();
        if (glossary == null) {
            return out;
        }
        int i = 1;
        for (var entry : glossary.entrySet()) {
            String term = entry.getKey();
            String text = entry.getValue();
            String folded = TextFeatures.fold(text);
            boolean normative = folded.contains("regra") || folded.contains("condicao") || folded.contains(" so ")
                    || folded.startsWith("so ") || folded.contains("deve") || folded.contains("nao pode")
                    || folded.contains("uma vez") || folded.contains("apenas") || folded.contains("somente")
                    || folded.contains("recusad") || folded.contains("garant");
            List<String> matched = new ArrayList<>();
            String t = term.toLowerCase(Locale.ROOT);
            for (NodeFact n : nodes) {
                String hay = (n.label() + " " + n.resource()).toLowerCase(Locale.ROOT);
                if (hay.contains(t)) {
                    matched.add(n.nodeId());
                }
            }
            // comportamento DOCUMENTADO de uma funcionalidade observada também é verificável:
            // "order-processor: recebe o pedido, grava no DynamoDB e publica na fila"
            boolean functionality = nodes.stream()
                    .anyMatch(n -> matched.contains(n.nodeId())
                            && (n.kind() == NodeKind.LAMBDA || n.kind() == NodeKind.BUSINESS
                            || n.kind() == NodeKind.HTTP_SERVER));
            if (normative || functionality) {
                out.add(new RuleFact("R" + i++, term, text, matched, normative));
            }
        }
        return out;
    }

    public NodeFact node(String nodeId) {
        for (NodeFact n : nodes) {
            if (n.nodeId().equals(nodeId)) {
                return n;
            }
        }
        return null;
    }

    public List<NodeFact> failedNodes() {
        return nodes.stream().filter(NodeFact::failed).toList();
    }

    /** Produtores (SQS/SNS publish) sem consumidor observado na mesma árvore. */
    public int producersWithoutConsumer() {
        int count = 0;
        for (NodeFact p : nodes) {
            if (!p.producer()) {
                continue;
            }
            boolean hasConsumer = nodes.stream().anyMatch(c -> p.nodeId().equals(c.parentId())
                    && (c.kind() == NodeKind.LAMBDA || c.kind() == NodeKind.HTTP_SERVER || c.asyncConsumer()));
            if (!hasConsumer) {
                count++;
            }
        }
        return count;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
