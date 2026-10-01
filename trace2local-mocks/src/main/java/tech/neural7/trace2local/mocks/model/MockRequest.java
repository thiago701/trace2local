package tech.neural7.trace2local.mocks.model;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.internal.JsonSupport;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Requisição recebida pelo mock (ou sintetizada a partir de um stub para destinos estáticos).
 *
 * @param path caminho RELATIVO à API (sem o prefixo do binding)
 */
public record MockRequest(String method, String path, Map<String, List<String>> query,
                          Map<String, List<String>> headers, String body) {

    public MockRequest {
        method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        path = path == null || path.isEmpty() ? "/" : path;
        query = query == null ? Map.of() : query;
        headers = headers == null ? Map.of() : headers;
    }

    public static MockRequest of(String method, String path) {
        return new MockRequest(method, path, Map.of(), Map.of(), null);
    }

    /** Cabeçalho (case-insensitive), primeiro valor. */
    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    public String queryParam(String name) {
        List<String> v = query.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    /** Corpo como JSON (ou {@code null} se não for JSON). */
    public JsonNode bodyJson() {
        return body == null || body.isBlank() ? null : JsonSupport.parse(body);
    }
}
