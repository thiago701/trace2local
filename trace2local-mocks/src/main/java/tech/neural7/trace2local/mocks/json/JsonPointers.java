package tech.neural7.trace2local.mocks.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Edição de JSON por JSON Pointer (RFC 6901) sem mutar a entrada. */
public final class JsonPointers {

    private JsonPointers() {}

    /** Define o valor em {@code pointer}, criando objetos intermediários. Ponteiro vazio troca o documento. */
    public static JsonNode set(JsonNode root, String pointer, JsonNode value) {
        JsonNode v = value == null ? JsonNodeFactory.instance.nullNode() : value;
        if (pointer == null || pointer.isEmpty()) {
            return v.deepCopy();
        }
        JsonNode copy = root == null || !root.isContainerNode() ? JsonNodeFactory.instance.objectNode() : root.deepCopy();
        List<String> tokens = tokens(pointer);
        JsonNode cur = copy;
        for (int i = 0; i < tokens.size() - 1; i++) {
            String t = tokens.get(i);
            JsonNode next = child(cur, t);
            if (next == null || !next.isContainerNode()) {
                next = JsonNodeFactory.instance.objectNode();
                put(cur, t, next);
            }
            cur = next;
        }
        put(cur, tokens.get(tokens.size() - 1), v.deepCopy());
        return copy;
    }

    /** Remove o campo/elemento (ausente = sem efeito). */
    public static JsonNode remove(JsonNode root, String pointer) {
        if (root == null || pointer == null || pointer.isEmpty() || !root.isContainerNode()) {
            return root;
        }
        JsonNode copy = root.deepCopy();
        List<String> tokens = tokens(pointer);
        JsonNode parent = copy;
        for (int i = 0; i < tokens.size() - 1; i++) {
            parent = child(parent, tokens.get(i));
            if (parent == null) {
                return copy;
            }
        }
        String last = tokens.get(tokens.size() - 1);
        if (parent instanceof ObjectNode o) {
            o.remove(last);
        } else if (parent instanceof ArrayNode a) {
            int idx = index(last);
            if (idx >= 0 && idx < a.size()) {
                a.remove(idx);
            }
        }
        return copy;
    }

    /** Lê o valor ({@code null} se ausente). */
    public static JsonNode get(JsonNode root, String pointer) {
        if (root == null) {
            return null;
        }
        JsonNode at = root.at(pointer == null ? "" : pointer);
        return at.isMissingNode() ? null : at;
    }

    /**
     * Todos os campos-folha escalares (até {@code maxDepth}), como ponteiro → valor.
     * Arrays contribuem com o primeiro elemento ({@code /items/0/...}).
     */
    public static void leaves(JsonNode node, String prefix, int maxDepth, Map<String, JsonNode> out) {
        if (node == null || maxDepth < 0) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String p = prefix + "/" + escape(e.getKey());
                if (e.getValue().isValueNode()) {
                    out.put(p, e.getValue());
                } else {
                    leaves(e.getValue(), p, maxDepth - 1, out);
                }
            });
        } else if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            String p = prefix + "/0";
            if (first.isValueNode()) {
                out.put(p, first);
            } else {
                leaves(first, p, maxDepth - 1, out);
            }
        }
    }

    public static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static List<String> tokens(String pointer) {
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("JSON Pointer deve começar com '/': " + pointer);
        }
        List<String> out = new ArrayList<>();
        for (String raw : pointer.substring(1).split("/", -1)) {
            out.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return out;
    }

    private static JsonNode child(JsonNode node, String token) {
        if (node instanceof ObjectNode o) {
            return o.get(token);
        }
        if (node instanceof ArrayNode a) {
            int idx = index(token);
            return idx >= 0 && idx < a.size() ? a.get(idx) : null;
        }
        return null;
    }

    private static void put(JsonNode container, String token, JsonNode value) {
        if (container instanceof ObjectNode o) {
            o.set(token, value);
        } else if (container instanceof ArrayNode a) {
            if ("-".equals(token)) {
                a.add(value);
                return;
            }
            int idx = index(token);
            if (idx >= 0 && idx < a.size()) {
                a.set(idx, value);
            } else if (idx == a.size()) {
                a.add(value);
            } else {
                throw new IllegalArgumentException("índice fora do array: " + token);
            }
        }
    }

    private static int index(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
