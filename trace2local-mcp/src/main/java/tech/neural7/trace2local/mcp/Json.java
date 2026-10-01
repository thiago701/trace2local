package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/** Jackson compartilhado: saída compacta, uma mensagem por linha (exigência do stdio do MCP). */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String pretty(JsonNode node) {
        try {
            return PRETTY.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode parse(String text) throws JsonProcessingException {
        return MAPPER.readTree(text);
    }

    /** JSON quando der; senão o texto cru (respostas não-JSON nunca derrubam a ferramenta). */
    static JsonNode parseOrText(String text) {
        try {
            JsonNode n = MAPPER.readTree(text);
            return n == null ? TextNode.valueOf(text) : n;
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(text);
        }
    }

    /** Schema JSON de objeto: {@code schema(required, "nome", propSchema, ...)}. */
    static ObjectNode schema(String[] required, Object... props) {
        ObjectNode s = obj().put("type", "object");
        ObjectNode p = s.putObject("properties");
        for (int i = 0; i + 1 < props.length; i += 2) {
            p.set((String) props[i], (JsonNode) props[i + 1]);
        }
        if (required != null && required.length > 0) {
            ArrayNode r = s.putArray("required");
            for (String q : required) {
                r.add(q);
            }
        }
        s.put("additionalProperties", false);
        return s;
    }

    static ObjectNode str(String description) {
        return obj().put("type", "string").put("description", description);
    }

    static ObjectNode enumStr(String description, String... values) {
        ObjectNode n = str(description);
        ArrayNode e = n.putArray("enum");
        for (String v : values) {
            e.add(v);
        }
        return n;
    }

    static ObjectNode integer(String description, int min, int max, int def) {
        return obj().put("type", "integer").put("description", description).put("minimum", min).put("maximum", max).put("default", def);
    }

    static ObjectNode bool(String description, boolean def) {
        return obj().put("type", "boolean").put("description", description).put("default", def);
    }

    static ObjectNode anyObject(String description) {
        return obj().put("type", "object").put("description", description);
    }

    static ObjectNode stringMap(String description) {
        ObjectNode n = obj().put("type", "object").put("description", description);
        n.putObject("additionalProperties").put("type", "string");
        return n;
    }

    static ObjectNode stringArray(String description) {
        ObjectNode n = obj().put("type", "array").put("description", description);
        n.putObject("items").put("type", "string");
        return n;
    }
}
