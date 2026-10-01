package tech.neural7.trace2local.mocks.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Configuração já interpretada e validada de um plugin (acesso tipado). */
public final class MockConfig {

    private final Map<String, Object> values;
    private final Map<String, ?> originals;

    MockConfig(Map<String, Object> values, Map<String, ?> originals) {
        this.values = values;
        this.originals = originals;
    }

    public static MockConfig empty() {
        return new MockConfig(Map.of(), Map.of());
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public int getInt(String key) {
        return ((Number) values.get(key)).intValue();
    }

    public long getLong(String key) {
        return ((Number) values.get(key)).longValue();
    }

    public double getDouble(String key) {
        return ((Number) values.get(key)).doubleValue();
    }

    public boolean getBoolean(String key) {
        return Boolean.TRUE.equals(values.get(key));
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(String key) {
        Object v = values.get(key);
        return v == null ? List.of() : (List<String>) v;
    }

    public JsonNode getJson(String key) {
        return (JsonNode) values.get(key);
    }

    public Password getPassword(String key) {
        return (Password) values.get(key);
    }

    public boolean has(String key) {
        return values.get(key) != null;
    }

    public Map<String, ?> originals() {
        return Collections.unmodifiableMap(originals);
    }
}
