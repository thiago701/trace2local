package tech.neural7.trace2local.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.spi.EndpointDescriptor;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Inferência de schema do {@code @RequestBody} (SPEC §4.8, estratégia 2).
 * Honestidade: usa {@code RecordComponent} (records — sem tocar em campos
 * privados); se não der, devolve {@code null} e a UI mostra o aviso — nunca um
 * schema inventado. Em Native Image funciona para os tipos catalogados pelo
 * {@link Trace2LocalRuntimeHints}.
 */
final class SchemaInferrer {

    private SchemaInferrer() {}

    static JsonNode schemaOf(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isRecord()) {
                return recordSchema(clazz);
            }
            if (clazz.isEnum() || isSimple(clazz)) {
                return simpleSchema(clazz);
            }
            return null; // classes: exige reflexão sobre campos privados — fora do contrato
        }
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw == List.class || raw == java.util.Collection.class || raw == java.util.Set.class) {
                ObjectNode schema = JsonSupport.MAPPER.createObjectNode();
                schema.put("type", "array");
                schema.set("items", schemaOf(pt.getActualTypeArguments()[0]));
                return schema;
            }
        }
        return null;
    }

    static String sampleBodyOf(Type type) {
        JsonNode schema = schemaOf(type);
        if (schema == null) {
            return null;
        }
        JsonNode sample = buildSample(schema);
        return sample == null ? null : JsonSupport.write(sample);
    }

    private static JsonNode recordSchema(Class<?> recordType) {
        ObjectNode schema = JsonSupport.MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");
        for (RecordComponent component : recordType.getRecordComponents()) {
            properties.set(component.getName(), schemaOf(component.getGenericType()));
            required.add(component.getName());
        }
        return schema;
    }

    private static JsonNode simpleSchema(Class<?> type) {
        ObjectNode schema = JsonSupport.MAPPER.createObjectNode();
        if (type == String.class || type == Character.class || type == char.class) {
            schema.put("type", "string");
        } else if (type == Boolean.class || type == boolean.class) {
            schema.put("type", "boolean");
        } else if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            schema.put("type", "number");
        } else {
            schema.put("type", "string");
        }
        return schema;
    }

    private static boolean isSimple(Class<?> type) {
        return type == String.class
                || type == Boolean.class || type == boolean.class
                || Number.class.isAssignableFrom(type)
                || type == int.class || type == long.class || type == double.class
                || type == float.class || type == short.class || type == byte.class;
    }

    private static JsonNode buildSample(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return null;
        }
        String type = schema.path("type").asText();
        return switch (type) {
            case "object" -> {
                ObjectNode out = JsonSupport.MAPPER.createObjectNode();
                schema.path("properties").fields().forEachRemaining(e ->
                        out.set(e.getKey(), sampleValue(e.getValue())));
                yield out;
            }
            case "array" -> {
                ArrayNode out = JsonSupport.MAPPER.createArrayNode();
                out.add(sampleValue(schema.path("items")));
                yield out;
            }
            case "string" -> JsonSupport.MAPPER.getNodeFactory().textNode("string");
            case "boolean" -> JsonSupport.MAPPER.getNodeFactory().booleanNode(true);
            case "number" -> JsonSupport.MAPPER.getNodeFactory().numberNode(0);
            default -> JsonSupport.MAPPER.nullNode();
        };
    }

    private static JsonNode sampleValue(JsonNode schema) {
        JsonNode sample = buildSample(schema);
        return sample != null ? sample : JsonSupport.MAPPER.nullNode();
    }
}
