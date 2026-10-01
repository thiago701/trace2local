package tech.neural7.trace2local.examples.pix.infra;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Jackson compartilhado (sem reflexão sobre classes de domínio: só árvore JSON — amigável ao native-image). */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {}

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode parse(String text) {
        try {
            return text == null || text.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON inválido");
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
