package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Leitura compacta das respostas da API para um agente: a árvore vira um <i>outline</i> com
 * ids navegáveis ({@code [nodeId]}), marcas de erro (✕), Δ de dados, resposta simulada
 * (SIM/↪) e espera em fila (⧗). Texto curto e estável — o modelo lê menos e erra menos.
 */
final class Formats {

    /** Marca do Mock Connect no passo (atributo do contrato público {@code t2l.mock}). */
    static final String MOCK_ATTR = "t2l.mock";

    private Formats() {
    }

    static String ms(long v) {
        if (v < 1000) {
            return v + " ms";
        }
        if (v < 120_000) {
            return String.format(Locale.ROOT, "%.2f s", v / 1000.0);
        }
        return String.format(Locale.ROOT, "%.1f min", v / 60000.0);
    }

    static long millis(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return 0;
        }
        if (n.isNumber()) {
            return n.asLong();
        }
        try {
            return Duration.parse(n.asText()).toMillis();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    static Instant instant(JsonNode n) {
        try {
            return n == null || n.isNull() ? null : Instant.parse(n.asText());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Percorre a árvore em profundidade (ordem de início), com a profundidade. */
    static void walk(JsonNode roots, java.util.function.BiConsumer<JsonNode, Integer> visitor) {
        for (JsonNode r : roots) {
            walk(r, 0, visitor);
        }
    }

    private static void walk(JsonNode n, int depth, java.util.function.BiConsumer<JsonNode, Integer> visitor) {
        visitor.accept(n, depth);
        for (JsonNode c : n.path("children")) {
            walk(c, depth + 1, visitor);
        }
    }

    static Map<String, JsonNode> index(JsonNode execution) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        walk(execution.path("roots"), (n, d) -> byId.put(n.path("nodeId").asText(), n));
        return byId;
    }

    /** Caminho raiz → nó (pais conhecidos). */
    static List<JsonNode> pathTo(JsonNode execution, String nodeId) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode r : execution.path("roots")) {
            if (pathTo(r, nodeId, out)) {
                return out;
            }
        }
        return out;
    }

    private static boolean pathTo(JsonNode n, String nodeId, List<JsonNode> acc) {
        acc.add(n);
        if (nodeId.equals(n.path("nodeId").asText())) {
            return true;
        }
        for (JsonNode c : n.path("children")) {
            if (pathTo(c, nodeId, acc)) {
                return true;
            }
        }
        acc.remove(acc.size() - 1);
        return false;
    }

    static String summaryLine(JsonNode s) {
        return s.path("executionId").asText() + " · " + s.path("rootLabel").asText("(sem rótulo)")
                + " · " + s.path("status").asText("?") + " · " + ms(millis(s.path("duration")))
                + " · " + s.path("nodeCount").asInt() + " passos · " + s.path("trigger").asText("")
                + " · " + s.path("startedAt").asText("");
    }

    static String mockMark(JsonNode node) {
        String raw = node.path("attributes").path(MOCK_ATTR).asText("");
        if (raw.isBlank()) {
            return "";
        }
        boolean passthrough = raw.contains("passthrough=true") || raw.contains("proxy=true");
        boolean simulated = !passthrough || raw.contains("variation=");
        return simulated ? " [SIM " + raw + "]" : " [↪ repasse da API real: " + raw + "]";
    }

    static String mutationShort(JsonNode m, McpConfig.DataMode mode) {
        if (m == null || m.isNull() || m.isMissingNode()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" Δ ").append(m.path("kind").asText("?"));
        String target = m.path("target").asText("");
        if (!target.isBlank()) {
            sb.append(' ').append(target);
        }
        String key = m.path("key").asText("");
        if (!key.isBlank()) {
            sb.append(" chave=").append(truncate(key, 60));
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode d : m.path("deltas")) {
            String p = d.path("path").asText();
            if (mode == McpConfig.DataMode.FULL) {
                paths.add(p + ": " + truncate(Json.write(d.path("before")), 40) + " → " + truncate(Json.write(d.path("after")), 40));
            } else {
                paths.add(p);
            }
        }
        if (!paths.isEmpty()) {
            sb.append(" campos=").append(String.join(", ", paths.subList(0, Math.min(paths.size(), 8))));
            if (paths.size() > 8) {
                sb.append(" (+").append(paths.size() - 8).append(')');
            }
        }
        String fidelity = m.path("fidelity").asText("");
        if (!fidelity.isBlank() && !"EXACT".equals(fidelity)) {
            sb.append(" (").append(fidelity.toLowerCase(Locale.ROOT)).append(')');
        }
        return sb.toString();
    }

    /** Outline da execução; devolve o texto e conta os nós omitidos. */
    static String outline(JsonNode execution, int maxNodes, McpConfig.DataMode mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Execução ").append(execution.path("executionId").asText())
                .append(" · ").append(execution.path("status").asText())
                .append(" · ").append(ms(millis(execution.path("duration"))))
                .append(" · gatilho ").append(execution.path("trigger").asText(""))
                .append(" · trace ").append(execution.path("traceId").asText(""))
                .append('\n');
        JsonNode metrics = execution.path("metrics");
        if (!metrics.isMissingNode()) {
            sb.append("passos ").append(metrics.path("nodeCount").asInt())
                    .append(" · profundidade ").append(metrics.path("maxDepth").asInt())
                    .append(" · spans perdidos ").append(metrics.path("lostSpans").asInt())
                    .append(" · eventos descartados ").append(metrics.path("droppedEvents").asInt()).append('\n');
        }
        for (JsonNode w : execution.path("warnings")) {
            sb.append("AVISO ").append(w.path("kind").asText()).append(": ").append(w.path("message").asText()).append('\n');
        }
        sb.append("Legenda: [nodeId] TIPO rótulo — duração (self) · ✕ erro · Δ dado alterado · SIM resposta simulada · ↪ repasse · ⧗ espera em fila\n");
        int[] shown = {0};
        int[] total = {0};
        walkWithParent(execution.path("roots"), null, 0, (n, parent, depth) -> {
            total[0]++;
            if (shown[0] >= maxNodes) {
                return;
            }
            shown[0]++;
            String indent = "  ".repeat(depth);
            if (parent != null && isQueue(parent) && isEntry(n)) {
                Instant ps = instant(parent.path("startedAt"));
                Instant cs = instant(n.path("startedAt"));
                if (ps != null && cs != null) {
                    long wait = Math.max(0, Duration.between(ps.plusMillis(millis(parent.path("totalTime"))), cs).toMillis());
                    sb.append(indent).append("⧗ fila ").append(ms(wait)).append('\n');
                }
            }
            sb.append(indent).append('[').append(n.path("nodeId").asText()).append("] ")
                    .append(n.path("kind").asText()).append(' ')
                    .append(truncate(n.path("label").asText("?"), 90))
                    .append(" — ").append(ms(millis(n.path("totalTime"))))
                    .append(" (self ").append(ms(millis(n.path("selfTime")))).append(')');
            String status = n.path("status").asText("OK");
            if (!"OK".equals(status)) {
                sb.append(' ').append(status);
            }
            JsonNode err = n.path("error");
            if (!err.isNull() && !err.isMissingNode()) {
                sb.append(" ✕ ").append(err.path("type").asText("erro")).append(": ")
                        .append(truncate(oneLine(err.path("message").asText("")), 160));
            }
            sb.append(mutationShort(n.path("mutation"), mode));
            sb.append(mockMark(n));
            sb.append('\n');
        });
        if (total[0] > shown[0]) {
            sb.append("… ").append(total[0] - shown[0]).append(" passo(s) omitido(s) — aumente maxNodes ou use get_step\n");
        }
        return sb.toString();
    }

    interface Visitor {
        void visit(JsonNode node, JsonNode parent, int depth);
    }

    static void walkWithParent(JsonNode nodes, JsonNode parent, int depth, Visitor v) {
        for (JsonNode n : nodes) {
            v.visit(n, parent, depth);
            walkWithParent(n.path("children"), n, depth + 1, v);
        }
    }

    static boolean isQueue(JsonNode n) {
        String k = n.path("kind").asText();
        return k.equals("SQS") || k.equals("SNS");
    }

    static boolean isEntry(JsonNode n) {
        String k = n.path("kind").asText();
        return k.equals("LAMBDA") || k.equals("HTTP_SERVER");
    }

    /** Host lógico de uma chamada externa, a partir da URL completa ou do rótulo. */
    static String hostOf(JsonNode node) {
        JsonNode attrs = node.path("attributes");
        for (var it = attrs.fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equals("url.full")) {
                try {
                    String h = URI.create(e.getValue().asText()).getHost();
                    if (h != null) {
                        return h;
                    }
                } catch (IllegalArgumentException ignored) {
                    // URL malformada: tenta o próximo sinal
                }
            }
        }
        return null;
    }

    static void forEachArray(JsonNode n, Consumer<JsonNode> c) {
        if (n != null && n.isArray()) {
            n.forEach(c);
        }
    }
}
