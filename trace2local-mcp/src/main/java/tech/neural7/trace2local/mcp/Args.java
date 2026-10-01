package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Leitura validada dos argumentos de uma ferramenta (erro → {@code isError} com mensagem clara). */
final class Args {

    private final JsonNode node;

    Args(JsonNode node) {
        this.node = node == null || node.isNull() ? Json.obj() : node;
    }

    String required(String name) {
        String v = optional(name);
        if (v == null) {
            throw new IllegalArgumentException("argumento obrigatório ausente: " + name);
        }
        return v;
    }

    String optional(String name) {
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isValueNode() ? v.asText() : v.toString();
        return s.isBlank() ? null : s.trim();
    }

    String oneOf(String name, String def, String... allowed) {
        String v = optional(name);
        if (v == null) {
            return def;
        }
        for (String a : allowed) {
            if (a.equalsIgnoreCase(v)) {
                return a;
            }
        }
        throw new IllegalArgumentException(name + " deve ser um de " + String.join(", ", allowed) + " (recebido: " + v + ")");
    }

    int integer(String name, int def, int min, int max) {
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) {
            return def;
        }
        int i;
        if (v.isNumber()) {
            i = v.asInt();
        } else {
            try {
                i = Integer.parseInt(v.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " deve ser inteiro");
            }
        }
        return Math.max(min, Math.min(max, i));
    }

    boolean bool(String name, boolean def) {
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) {
            return def;
        }
        return v.isBoolean() ? v.asBoolean() : "true".equalsIgnoreCase(v.asText());
    }

    JsonNode raw(String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() ? null : v;
    }

    ObjectNode stringMap(String name) {
        JsonNode v = node.get(name);
        ObjectNode out = Json.obj();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isObject()) {
            throw new IllegalArgumentException(name + " deve ser um objeto {chave: valor}");
        }
        v.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
        return out;
    }

    List<String> strings(String name) {
        JsonNode v = node.get(name);
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (v.isArray()) {
            v.forEach(x -> out.add(x.asText()));
        } else {
            for (String s : v.asText().split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
