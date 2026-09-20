package tech.neural7.tracevanta.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

/** Conversão AttributeValue ⇄ JsonNode para o TVEM (before/after do delta). */
final class AttributeValues {

    private AttributeValues() {}

    static JsonNode toJson(Map<String, AttributeValue> item) {
        ObjectNode out = JsonSupportHolder.MAPPER.createObjectNode();
        if (item == null) {
            return out;
        }
        for (Map.Entry<String, AttributeValue> e : item.entrySet()) {
            out.set(e.getKey(), toJson(e.getValue()));
        }
        return out;
    }

    private static JsonNode toJson(AttributeValue value) {
        if (value == null) {
            return JsonSupportHolder.MAPPER.nullNode();
        }
        if (value.s() != null) {
            return JsonSupportHolder.MAPPER.getNodeFactory().textNode(value.s());
        }
        if (value.n() != null) {
            try {
                return JsonSupportHolder.MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal(value.n()));
            } catch (NumberFormatException e) {
                return JsonSupportHolder.MAPPER.getNodeFactory().textNode(value.n());
            }
        }
        if (value.bool() != null) {
            return JsonSupportHolder.MAPPER.getNodeFactory().booleanNode(value.bool());
        }
        if (Boolean.TRUE.equals(value.nul())) {
            return JsonSupportHolder.MAPPER.nullNode();
        }
        if (value.hasL()) {
            var array = JsonSupportHolder.MAPPER.createArrayNode();
            for (AttributeValue child : value.l()) {
                array.add(toJson(child));
            }
            return array;
        }
        if (value.hasM()) {
            return toJson(value.m());
        }
        if (value.hasSs()) {
            var array = JsonSupportHolder.MAPPER.createArrayNode();
            for (String child : value.ss()) {
                array.add(child);
            }
            return array;
        }
        if (value.hasNs()) {
            var array = JsonSupportHolder.MAPPER.createArrayNode();
            for (String child : value.ns()) {
                array.add(child);
            }
            return array;
        }
        if (value.hasBs()) {
            var array = JsonSupportHolder.MAPPER.createArrayNode();
            for (var child : value.bs()) {
                array.add(java.util.Base64.getEncoder().encodeToString(child.asByteArray()));
            }
            return array;
        }
        return JsonSupportHolder.MAPPER.nullNode();
    }

    /** Chave legível do item ("ORDER#88291"): valores da chave unidos por #. */
    static String keyOf(Map<String, AttributeValue> key) {
        if (key == null || key.isEmpty()) {
            return "?";
        }
        List<String> values = key.values().stream().map(AttributeValues::scalar).toList();
        return String.join("#", values);
    }

    private static String scalar(AttributeValue value) {
        if (value.s() != null) return value.s();
        if (value.n() != null) return value.n();
        return "?";
    }
}
