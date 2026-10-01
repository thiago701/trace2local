package tech.neural7.trace2local.mocks.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.model.Fault;
import tech.neural7.trace2local.mocks.model.MockResponse;
import tech.neural7.trace2local.mocks.model.RequestMatcher;
import tech.neural7.trace2local.mocks.model.Stub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Conversão de/para o formato de <i>stub mapping</i> do WireMock — o padrão de fato
 * de mock HTTP na JVM. Importar permite reaproveitar mocks que o time já tem;
 * exportar permite versionar no repositório as variações descobertas no Trace2Local.
 *
 * <p>Suportado: {@code url}, {@code urlPath}, {@code urlPathTemplate}, {@code urlPathPattern},
 * {@code urlPattern}; query/headers {@code equalTo}/{@code matches}; {@code bodyPatterns}
 * {@code matchesJsonPath} com {@code equalTo}/{@code matches}; {@code jsonBody}/{@code body};
 * {@code fixedDelayMilliseconds}; {@code fault}. O resto vira nota no stub (nunca silêncio).
 */
public final class WireMockFormat {

    /** Metadata gravada em cada mapping exportado: {@code {"trace2local": {"binding", "stub", "origin"}}}. */
    public static final String METADATA_ROOT = "trace2local";

    private WireMockFormat() {}

    // ------------------------------------------------------------------ import

    /** Aceita um mapping, um array de mappings ou {@code {"mappings": [...]}}. */
    public static List<Stub> read(JsonNode doc, String origin) {
        List<Stub> out = new ArrayList<>();
        if (doc == null) {
            return out;
        }
        JsonNode list = doc.has("mappings") ? doc.get("mappings") : doc;
        if (list.isArray()) {
            int i = 0;
            for (JsonNode m : list) {
                out.add(readOne(m, origin, i++));
            }
        } else if (list.isObject()) {
            out.add(readOne(list, origin, 0));
        }
        return out;
    }

    static Stub readOne(JsonNode m, String origin, int index) {
        List<String> notes = new ArrayList<>();
        JsonNode req = m.path("request");
        String method = req.path("method").asText("ANY");
        String path;
        Map<String, String> query = new LinkedHashMap<>();
        if (req.hasNonNull("urlPathTemplate")) {
            path = req.get("urlPathTemplate").asText();
        } else if (req.hasNonNull("urlPath")) {
            path = req.get("urlPath").asText();
        } else if (req.hasNonNull("urlPathPattern")) {
            path = "re:" + req.get("urlPathPattern").asText();
        } else if (req.hasNonNull("urlPattern")) {
            path = "re:" + req.get("urlPattern").asText().replaceAll("\\\\\\?.*$", "");
            notes.add("urlPattern convertido para padrão de caminho (query ignorada)");
        } else if (req.hasNonNull("url")) {
            String url = req.get("url").asText();
            int q = url.indexOf('?');
            path = q >= 0 ? url.substring(0, q) : url;
            if (q >= 0) {
                for (String pair : url.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        query.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
            }
        } else {
            path = "/**";
        }
        List<RequestMatcher.Constraint> constraints = new ArrayList<>();
        req.path("queryParameters").fields().forEachRemaining(e -> {
            if (e.getValue().has("equalTo")) {
                query.put(e.getKey(), e.getValue().get("equalTo").asText());
            } else {
                notes.add("queryParameters." + e.getKey() + ": só equalTo é suportado — ignorado");
            }
        });
        req.path("headers").fields().forEachRemaining(e -> {
            String rx = regexOf(e.getValue());
            if (rx != null) {
                constraints.add(new RequestMatcher.Constraint(RequestMatcher.Constraint.Kind.HEADER, e.getKey(), rx));
            } else {
                notes.add("headers." + e.getKey() + ": operador não suportado — ignorado");
            }
        });
        for (JsonNode bp : req.path("bodyPatterns")) {
            JsonNode jp = bp.get("matchesJsonPath");
            if (jp != null && jp.isObject() && jp.has("expression")) {
                String pointer = jsonPathToPointer(jp.get("expression").asText());
                String rx = regexOf(jp);
                if (pointer != null && rx != null) {
                    constraints.add(new RequestMatcher.Constraint(RequestMatcher.Constraint.Kind.BODY, pointer, rx));
                    continue;
                }
            }
            notes.add("bodyPattern não suportado ignorado: " + bp);
        }
        JsonNode res = m.path("response");
        Map<String, String> headers = new LinkedHashMap<>();
        res.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        String body = null;
        if (res.has("jsonBody")) {
            body = JsonSupport.write(res.get("jsonBody"));
            headers.putIfAbsent("Content-Type", "application/json");
        } else if (res.has("body")) {
            body = res.get("body").asText();
        } else if (res.has("bodyFileName")) {
            notes.add("bodyFileName não suportado — corpo vazio (use jsonBody)");
        }
        if (res.has("transformers")) {
            notes.add("transformers do WireMock ignorados — use transforms do Trace2Local (ex.: template)");
        }
        Fault fault = switch (res.path("fault").asText("").toUpperCase(Locale.ROOT)) {
            case "CONNECTION_RESET_BY_PEER", "RANDOM_DATA_THEN_CLOSE" -> Fault.CONNECTION_RESET;
            case "EMPTY_RESPONSE", "MALFORMED_RESPONSE_CHUNK" -> Fault.EMPTY_RESPONSE;
            default -> Fault.NONE;
        };
        MockResponse response = new MockResponse(res.path("status").asInt(200), headers, body,
                res.path("fixedDelayMilliseconds").asLong(0), fault);
        String id = m.hasNonNull("id") ? m.get("id").asText()
                : m.hasNonNull("name") ? slug(m.get("name").asText()) : "wm-" + index;
        String op = (method.equals("ANY") ? "*" : method) + " " + path;
        return new Stub(id, m.hasNonNull("name") ? m.get("name").asText() : op,
                new RequestMatcher(method.equals("ANY") ? null : method, path, query, constraints),
                response, m.path("priority").asInt(5), origin, notes);
    }

    private static String regexOf(JsonNode op) {
        if (op.has("equalTo")) {
            return Pattern.quote(op.get("equalTo").asText());
        }
        if (op.has("matches")) {
            return op.get("matches").asText();
        }
        if (op.has("contains")) {
            return ".*" + Pattern.quote(op.get("contains").asText()) + ".*";
        }
        return null;
    }

    /** {@code $.a.b[0]} → {@code /a/b/0} (subconjunto simples de JSONPath). */
    static String jsonPathToPointer(String expr) {
        String e = expr.trim();
        if (!e.startsWith("$")) {
            return null;
        }
        e = e.substring(1);
        StringBuilder sb = new StringBuilder();
        var m = Pattern.compile("\\.([A-Za-z0-9_\\-]+)|\\[(\\d+)]|\\['([^']+)']").matcher(e);
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) {
                return null;
            }
            String token = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            sb.append('/').append(JsonPointers.escape(token));
            pos = m.end();
        }
        return pos == e.length() ? sb.toString() : null;
    }

    // ------------------------------------------------------------------ export

    /** Stub → mapping do WireMock, com metadata de rastreabilidade ({@code trace2local.binding}). */
    public static ObjectNode write(Stub stub, String binding, int priorityOverride) {
        ObjectNode m = JsonSupport.MAPPER.createObjectNode();
        m.put("name", stub.operation());
        m.put("priority", priorityOverride > 0 ? priorityOverride : stub.priority());
        ObjectNode req = m.putObject("request");
        RequestMatcher rm = stub.request();
        req.put("method", rm.method() == null ? "ANY" : rm.method());
        String path = rm.path();
        if (path.startsWith("re:")) {
            req.put("urlPathPattern", path.substring(3));
        } else if (path.contains("*")) {
            req.put("urlPathPattern", globToRegex(path));
        } else if (path.contains("{")) {
            req.put("urlPathTemplate", path);
        } else {
            req.put("urlPath", path);
        }
        if (!rm.query().isEmpty()) {
            ObjectNode q = req.putObject("queryParameters");
            rm.query().forEach((k, v) -> q.putObject(k).put("equalTo", v));
        }
        ArrayNode bodyPatterns = null;
        for (RequestMatcher.Constraint c : rm.constraints()) {
            if (c.kind() == RequestMatcher.Constraint.Kind.HEADER) {
                ObjectNode h = req.has("headers") ? (ObjectNode) req.get("headers") : req.putObject("headers");
                h.putObject(c.key()).put("matches", c.regex());
            } else {
                if (bodyPatterns == null) {
                    bodyPatterns = req.putArray("bodyPatterns");
                }
                bodyPatterns.addObject().putObject("matchesJsonPath")
                        .put("expression", pointerToJsonPath(c.key())).put("matches", c.regex());
            }
        }
        ObjectNode res = m.putObject("response");
        MockResponse r = stub.response();
        res.put("status", r.status());
        if (!r.headers().isEmpty()) {
            ObjectNode h = res.putObject("headers");
            r.headers().forEach(h::put);
        }
        JsonNode json = r.bodyJson();
        if (json != null) {
            res.set("jsonBody", json);
        } else if (r.body() != null) {
            res.put("body", r.body());
        }
        if (r.delayMs() > 0) {
            res.put("fixedDelayMilliseconds", r.delayMs());
        }
        switch (r.fault()) {
            case CONNECTION_RESET -> res.put("fault", "CONNECTION_RESET_BY_PEER");
            case EMPTY_RESPONSE -> res.put("fault", "EMPTY_RESPONSE");
            default -> { /* TIMEOUT = atraso fixo (já em fixedDelayMilliseconds) */ }
        }
        ObjectNode meta = m.putObject("metadata").putObject("trace2local");
        meta.put("binding", binding);
        meta.put("stub", stub.id());
        if (stub.origin() != null) {
            meta.put("origin", stub.origin());
        }
        return m;
    }

    static String pointerToJsonPath(String pointer) {
        StringBuilder sb = new StringBuilder("$");
        for (String t : pointer.substring(1).split("/")) {
            String token = t.replace("~1", "/").replace("~0", "~");
            if (token.matches("\\d+")) {
                sb.append('[').append(token).append(']');
            } else if (token.matches("[A-Za-z0-9_\\-]+")) {
                sb.append('.').append(token);
            } else {
                sb.append("['").append(token.replace("'", "\\'")).append("']");
            }
        }
        return sb.toString();
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        String[] parts = glob.split("/", -1);
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.equals("**")) {
                sb.append("(?:/.*)?");
            } else {
                sb.append('/').append(p.equals("*") ? "[^/]+" : Pattern.quote(p));
            }
        }
        return sb.toString();
    }

    static String slug(String s) {
        String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return out.isEmpty() ? "stub" : out.length() > 60 ? out.substring(0, 60) : out;
    }
}
