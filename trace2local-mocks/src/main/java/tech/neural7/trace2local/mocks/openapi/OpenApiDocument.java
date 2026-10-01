package tech.neural7.trace2local.mocks.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import tech.neural7.trace2local.internal.JsonSupport;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Leitor enxuto de OpenAPI 3.x (JSON ou YAML) — só o que mock e conselheiro
 * precisam: servidores, operações, respostas, exemplos nomeados, schemas com
 * {@code $ref} local, {@code allOf}/{@code oneOf}/{@code anyOf} e enums.
 * Sem dependência de um parser OpenAPI completo (e sem resolver {@code $ref} remoto:
 * nada de rede a partir de um contrato).
 */
public final class OpenApiDocument {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete", "head", "options");

    private final JsonNode root;
    private final String source;

    private OpenApiDocument(JsonNode root, String source) {
        this.root = root;
        this.source = source;
    }

    public static OpenApiDocument load(Path file) throws IOException {
        String text = Files.readString(file);
        return parse(text, file.getFileName().toString());
    }

    public static OpenApiDocument parse(String text, String source) throws IOException {
        String t = text.stripLeading();
        JsonNode node = t.startsWith("{") ? JsonSupport.MAPPER.readTree(t) : YAML.readTree(t);
        if (node == null || !node.has("paths")) {
            throw new IOException("não parece um OpenAPI 3.x (sem 'paths'): " + source);
        }
        if (node.has("swagger")) {
            throw new IOException("Swagger 2.0 não suportado — converta para OpenAPI 3 (" + source + ")");
        }
        return new OpenApiDocument(node, source);
    }

    public String source() {
        return source;
    }

    public String title() {
        return root.path("info").path("title").asText(source);
    }

    /** URLs de {@code servers[]} (sem variáveis resolvidas). */
    public List<String> serverUrls() {
        List<String> out = new ArrayList<>();
        root.path("servers").forEach(s -> out.add(s.path("url").asText("")));
        return out;
    }

    /** Hosts declarados (servers + extensão {@code x-trace2local-hosts}) — o catálogo indexa por eles. */
    public Set<String> hosts() {
        Set<String> out = new HashSet<>();
        for (String url : serverUrls()) {
            try {
                URI u = URI.create(url);
                if (u.getHost() != null) {
                    out.add(u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost());
                    out.add(u.getHost());
                }
            } catch (IllegalArgumentException ignored) {
                // URL relativa ou com variáveis: sem host
            }
        }
        root.path("info").path("x-trace2local-hosts").forEach(h -> out.add(h.asText()));
        root.path("x-trace2local-hosts").forEach(h -> out.add(h.asText()));
        return out;
    }

    /** Caminho-base do primeiro servidor ({@code https://x/api/v2} → {@code /api/v2}). */
    public String basePath() {
        for (String url : serverUrls()) {
            try {
                String p = url.contains("://") ? URI.create(url).getPath() : url;
                if (p != null && !p.isBlank() && !p.equals("/")) {
                    return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
                }
            } catch (IllegalArgumentException ignored) {
                // ignora servidor malformado
            }
        }
        return "";
    }

    /** Uma operação do contrato. */
    public record Operation(String method, String path, String operationId, String summary, JsonNode node) {
        public String key() {
            return method.toUpperCase(Locale.ROOT) + " " + path;
        }

        public String label() {
            return operationId != null ? operationId : key();
        }
    }

    public List<Operation> operations() {
        List<Operation> out = new ArrayList<>();
        root.path("paths").fields().forEachRemaining(p -> METHODS.forEach(m -> {
            JsonNode op = p.getValue().get(m);
            if (op != null && op.isObject()) {
                out.add(new Operation(m.toUpperCase(Locale.ROOT), p.getKey(),
                        op.hasNonNull("operationId") ? op.get("operationId").asText() : null,
                        op.path("summary").asText(null), op));
            }
        }));
        return out;
    }

    /** Parâmetro declarado (path/query/header) com exemplo, se houver. */
    public record Param(String name, String in, boolean required, String example, String description) {}

    public List<Param> parameters(Operation op) {
        List<Param> out = new ArrayList<>();
        for (JsonNode p : op.node().path("parameters")) {
            JsonNode r = resolve(p);
            JsonNode schema = resolve(r.path("schema"));
            String example = r.hasNonNull("example") ? r.get("example").asText()
                    : schema.hasNonNull("example") ? schema.get("example").asText()
                    : schema.hasNonNull("default") ? schema.get("default").asText() : null;
            out.add(new Param(r.path("name").asText(), r.path("in").asText("query"), r.path("required").asBoolean(false),
                    example, r.path("description").asText(null)));
        }
        return out;
    }

    /** Exemplo do corpo da requisição (nomeado → primeiro → gerado do schema) ou {@code null}. */
    public JsonNode requestExample(Operation op) {
        JsonNode content = resolve(op.node().path("requestBody")).path("content");
        JsonNode media = null;
        for (var it = content.fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().toLowerCase(Locale.ROOT).contains("json")) {
                media = e.getValue();
                break;
            }
        }
        if (media == null) {
            return null;
        }
        if (media.has("examples")) {
            for (JsonNode ex : media.get("examples")) {
                JsonNode r = resolve(ex);
                if (r.has("value")) {
                    return r.get("value");
                }
            }
        }
        if (media.has("example")) {
            return media.get("example");
        }
        return media.has("schema") ? sample(resolve(media.get("schema")), 0, identitySet()) : null;
    }

    /** Schema do corpo da requisição (resolvido) ou {@code null}. */
    public JsonNode requestSchema(Operation op) {
        JsonNode content = resolve(op.node().path("requestBody")).path("content");
        for (var it = content.fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().toLowerCase(Locale.ROOT).contains("json") && e.getValue().has("schema")) {
                return flatten(e.getValue().get("schema"));
            }
        }
        return null;
    }

    /** Status declarados da operação, na ordem do contrato. */
    public List<String> statuses(Operation op) {
        List<String> out = new ArrayList<>();
        op.node().path("responses").fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** Escolhe o status: código exato, faixa "2xx"/"4xx" ou "default". */
    public String pickStatus(Operation op, String selector) {
        List<String> statuses = statuses(op);
        if (statuses.contains(selector)) {
            return selector;
        }
        if (selector.length() == 3 && selector.endsWith("xx")) {
            for (String s : statuses) {
                if (s.charAt(0) == selector.charAt(0)) {
                    return s;
                }
            }
        }
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    /** Exemplos nomeados de uma resposta ({@code examples}) — nome → valor. */
    public Map<String, JsonNode> examples(Operation op, String status) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        JsonNode media = jsonMedia(op, status);
        if (media == null) {
            return out;
        }
        media.path("examples").fields().forEachRemaining(e -> {
            JsonNode ex = resolve(e.getValue());
            if (ex.has("value")) {
                out.put(e.getKey(), ex.get("value"));
            }
        });
        if (out.isEmpty() && media.has("example")) {
            out.put("example", media.get("example"));
        }
        return out;
    }

    /**
     * Corpo de exemplo: exemplo nomeado → primeiro exemplo → {@code example} do schema →
     * gerado a partir do schema. {@code null} se a resposta não tiver corpo JSON.
     */
    public JsonNode body(Operation op, String status, String exampleName) {
        Map<String, JsonNode> examples = examples(op, status);
        if (exampleName != null && examples.containsKey(exampleName)) {
            return examples.get(exampleName);
        }
        if (!examples.isEmpty()) {
            return examples.values().iterator().next();
        }
        JsonNode schema = schema(op, status);
        return schema == null ? null : sample(schema, 0, identitySet());
    }

    public JsonNode schema(Operation op, String status) {
        JsonNode media = jsonMedia(op, status);
        return media == null || !media.has("schema") ? null : resolve(media.get("schema"));
    }

    /**
     * Enums declarados na resposta (ponteiro → valores) — alimenta o conselheiro com
     * variações "previstas no contrato e nunca observadas".
     */
    public Map<String, List<String>> enums(Operation op, String status) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        JsonNode schema = schema(op, status);
        if (schema != null) {
            collectEnums(schema, "", out, 0, identitySet());
        }
        return out;
    }

    /** Campos obrigatórios (ponteiros) da resposta — para variações "campo obrigatório ausente". */
    public List<String> requiredFields(Operation op, String status) {
        List<String> out = new ArrayList<>();
        JsonNode schema = schema(op, status);
        if (schema != null) {
            JsonNode s = flatten(schema);
            s.path("required").forEach(r -> out.add("/" + r.asText()));
        }
        return out;
    }

    // ------------------------------------------------------------------ internos

    private static Set<JsonNode> identitySet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    private JsonNode jsonMedia(Operation op, String status) {
        if (status == null) {
            return null;
        }
        JsonNode response = resolve(op.node().path("responses").path(status));
        JsonNode content = response.path("content");
        Iterator<Map.Entry<String, JsonNode>> it = content.fields();
        JsonNode fallback = null;
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().toLowerCase(Locale.ROOT).contains("json")) {
                return e.getValue();
            }
            fallback = fallback == null ? e.getValue() : fallback;
        }
        return fallback;
    }

    /** Resolve {@code $ref} local ({@code #/components/...}); remoto é recusado (sem rede). */
    JsonNode resolve(JsonNode node) {
        JsonNode cur = node;
        for (int guard = 0; guard < 16 && cur != null && cur.has("$ref"); guard++) {
            String ref = cur.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                return JsonNodeFactory.instance.objectNode();
            }
            cur = root.at(ref.substring(1));
        }
        return cur == null || cur.isMissingNode() ? JsonNodeFactory.instance.objectNode() : cur;
    }

    /** allOf → objeto único (propriedades/required somados); oneOf/anyOf → primeira opção. */
    JsonNode flatten(JsonNode schema) {
        JsonNode s = resolve(schema);
        if (s.has("allOf")) {
            ObjectNode merged = JsonNodeFactory.instance.objectNode();
            ObjectNode props = merged.putObject("properties");
            ArrayNode required = merged.putArray("required");
            merged.put("type", "object");
            for (JsonNode part : s.get("allOf")) {
                JsonNode p = flatten(part);
                p.path("properties").fields().forEachRemaining(e -> props.set(e.getKey(), e.getValue()));
                p.path("required").forEach(required::add);
            }
            return merged;
        }
        for (String alt : List.of("oneOf", "anyOf")) {
            if (s.has(alt) && s.get(alt).isArray() && !s.get(alt).isEmpty()) {
                return flatten(s.get(alt).get(0));
            }
        }
        return s;
    }

    private JsonNode sample(JsonNode schema, int depth, Set<JsonNode> seen) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        JsonNode s = flatten(schema);
        if (depth > 6 || !seen.add(s)) {
            return f.nullNode();
        }
        try {
            if (s.has("example")) {
                return s.get("example");
            }
            if (s.has("default")) {
                return s.get("default");
            }
            if (s.has("enum") && !s.get("enum").isEmpty()) {
                return s.get("enum").get(0);
            }
            String type = s.path("type").isArray() ? s.path("type").get(0).asText() : s.path("type").asText(
                    s.has("properties") ? "object" : s.has("items") ? "array" : "string");
            return switch (type) {
                case "object" -> {
                    ObjectNode o = f.objectNode();
                    s.path("properties").fields().forEachRemaining(e -> o.set(e.getKey(), sample(e.getValue(), depth + 1, seen)));
                    yield o;
                }
                case "array" -> {
                    ArrayNode a = f.arrayNode();
                    if (s.has("items")) {
                        a.add(sample(s.get("items"), depth + 1, seen));
                    }
                    yield a;
                }
                case "integer" -> f.numberNode(s.path("minimum").asLong(0));
                case "number" -> f.numberNode(s.path("minimum").asDouble(0.0));
                case "boolean" -> f.booleanNode(false);
                case "null" -> f.nullNode();
                default -> f.textNode(switch (s.path("format").asText("")) {
                    case "date-time" -> Instant.parse("2026-01-01T12:00:00Z").toString();
                    case "date" -> LocalDate.of(2026, 1, 1).toString();
                    case "uuid" -> "00000000-0000-4000-8000-000000000000";
                    case "email" -> "cliente@example.com";
                    case "uri", "url" -> "https://example.com";
                    default -> "string";
                });
            };
        } finally {
            seen.remove(s);
        }
    }

    private void collectEnums(JsonNode schema, String prefix, Map<String, List<String>> out, int depth, Set<JsonNode> seen) {
        JsonNode s = flatten(schema);
        if (depth > 6 || !seen.add(s)) {
            return;
        }
        if (s.has("enum")) {
            List<String> values = new ArrayList<>();
            s.get("enum").forEach(v -> values.add(v.asText()));
            out.put(prefix.isEmpty() ? "" : prefix, values);
        }
        s.path("properties").fields().forEachRemaining(e ->
                collectEnums(e.getValue(), prefix + "/" + e.getKey(), out, depth + 1, seen));
        if (s.has("items")) {
            collectEnums(s.get("items"), prefix + "/0", out, depth + 1, seen);
        }
        seen.remove(s);
    }
}
