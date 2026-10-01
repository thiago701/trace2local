package tech.neural7.trace2local.mocks.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.advisor.ContractCatalog;
import tech.neural7.trace2local.mocks.advisor.MockAdvisor;
import tech.neural7.trace2local.mocks.advisor.MockSuggestion;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigException;
import tech.neural7.trace2local.mocks.config.ConfigValue;
import tech.neural7.trace2local.mocks.runtime.BindingConfig;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;
import tech.neural7.trace2local.mocks.runtime.PluginRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * API REST do Mock Connect em {@code /api/mocks} — o mesmo desenho da REST API do
 * Kafka Connect ({@code /connectors}, {@code /connector-plugins}, {@code .../config/validate},
 * {@code .../status}, {@code pause}/{@code resume}/{@code restart}), mais o que é próprio
 * de mock: stubs efetivos, export WireMock, journal, rotas, contratos e sugestões.
 *
 * <p>Montada pelo servidor do Trace2Local sob o {@code RequestGuard} (Host allowlist,
 * {@code X-Trace2Local: 1} em mutações, token de UI) — nada aqui é público.
 */
public final class MockConnectRestHandler implements HttpHandler {

    private static final int MAX_BODY = 512 * 1024;
    /** Jackson com java.time (Instant em ISO-8601) — status e journal carregam instantes. */
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = JsonSupport.MAPPER.copy()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final MockConnectWorker worker;
    private final MockAdvisor advisor;
    private final ContractCatalog contracts;

    public MockConnectRestHandler(MockConnectWorker worker, MockAdvisor advisor, ContractCatalog contracts) {
        this.worker = worker;
        this.advisor = advisor;
        this.contracts = contracts;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // sem try-with-resources: o catch ainda precisa escrever a resposta de erro
        try {
            String path = ex.getRequestURI().getPath();
            int at = path.indexOf("/api/mocks");
            String rest = at < 0 ? "" : path.substring(at + "/api/mocks".length());
            List<String> seg = new ArrayList<>();
            for (String s : rest.split("/")) {
                if (!s.isEmpty()) {
                    seg.add(URLDecoder.decode(s, StandardCharsets.UTF_8));
                }
            }
            route(ex, ex.getRequestMethod().toUpperCase(Locale.ROOT), seg);
        } catch (MethodNotAllowed e) {
            write(ex, 405, error(e.getMessage()));
        } catch (ConfigException e) {
            ObjectNode err = error("configuração inválida");
            err.set("errors", JSON.valueToTree(e.errors()));
            write(ex, 400, err);
        } catch (IllegalStateException e) {
            write(ex, 409, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            write(ex, 400, error(e.getMessage()));
        } catch (RuntimeException e) {
            write(ex, 500, error("erro interno do Mock Connect: " + e.getClass().getSimpleName()));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex, String method, List<String> s) throws IOException {
        int n = s.size();
        if (n == 0) {
            require(ex, method, "GET");
            write(ex, 200, overview());
            return;
        }
        switch (s.get(0)) {
            case "plugins" -> plugins(ex, method, s);
            case "bindings" -> bindings(ex, method, s);
            case "validate" -> {
                require(ex, method, "PUT", "POST");
                JsonNode body = body(ex);
                String name = body.path("name").asText("binding");
                BindingConfig.Report report = worker.validate(name, flat(body.has("config") ? body.get("config") : body));
                write(ex, 200, JSON.valueToTree(report));
            }
            case "journal" -> {
                require(ex, method, "GET");
                write(ex, 200, JSON.valueToTree(worker.journal().list(query(ex, "binding"), intQuery(ex, "limit", 100))));
            }
            case "routes" -> {
                require(ex, method, "GET");
                write(ex, 200, JSON.valueToTree(worker.routes()));
            }
            case "contracts" -> {
                require(ex, method, "GET");
                ArrayNode list = JSON.createArrayNode();
                contracts.all().forEach(c -> list.addObject().put("path", c.path()).put("title", c.document().title())
                        .put("operations", c.document().operations().size())
                        .set("hosts", JSON.valueToTree(c.document().hosts())));
                write(ex, 200, list);
            }
            case "suggestions" -> suggestions(ex, method, s);
            default -> write(ex, 404, error("recurso desconhecido em /api/mocks"));
        }
    }

    private void plugins(HttpExchange ex, String method, List<String> s) throws IOException {
        PluginRegistry registry = worker.registry();
        if (s.size() == 1) {
            require(ex, method, "GET");
            ArrayNode list = JSON.createArrayNode();
            for (PluginRegistry.PluginInfo info : registry.plugins()) {
                ObjectNode p = JSON.valueToTree(info);
                worker.pluginConfig(info.name()).ifPresent(def -> p.set("config", definitions(def)));
                list.add(p);
            }
            write(ex, 200, list);
            return;
        }
        String name = s.get(1);
        if (s.size() == 3 && s.get(2).equals("config")) {
            require(ex, method, "GET");
            Optional<ConfigDef> def = worker.pluginConfig(name);
            if (def.isEmpty()) {
                write(ex, 404, error("plugin desconhecido: " + name));
                return;
            }
            write(ex, 200, definitions(def.get()));
            return;
        }
        if (s.size() == 4 && s.get(2).equals("config") && s.get(3).equals("validate")) {
            require(ex, method, "PUT", "POST");
            JsonNode body = body(ex);
            Optional<List<ConfigValue>> values = worker.validatePlugin(name, flat(body.has("config") ? body.get("config") : body));
            if (values.isEmpty()) {
                write(ex, 404, error("plugin desconhecido: " + name));
                return;
            }
            ObjectNode out = JSON.createObjectNode().put("name", name)
                    .put("errorCount", values.get().stream().filter(v -> !v.errors().isEmpty()).count());
            out.set("configs", JSON.valueToTree(values.get()));
            write(ex, 200, out);
            return;
        }
        write(ex, 404, error("recurso desconhecido em /api/mocks/plugins"));
    }

    private void bindings(HttpExchange ex, String method, List<String> s) throws IOException {
        if (s.size() == 1) {
            if (method.equals("GET")) {
                write(ex, 200, JSON.valueToTree(worker.list()));
                return;
            }
            require(ex, method, "POST");
            JsonNode body = body(ex);
            String name = body.path("name").asText(null);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("'name' é obrigatório");
            }
            if (!body.has("config") || !body.get("config").isObject()) {
                throw new IllegalArgumentException("'config' (objeto chave → valor) é obrigatório");
            }
            write(ex, 201, JSON.valueToTree(worker.create(name, flat(body.get("config")))));
            return;
        }
        String name = s.get(1);
        String sub = s.size() > 2 ? s.get(2) : "";
        switch (sub) {
            case "" -> {
                if (method.equals("DELETE")) {
                    write(ex, worker.delete(name) ? 200 : 404, JSON.createObjectNode().put("deleted", name));
                    return;
                }
                require(ex, method, "GET");
                writeOr404(ex, worker.get(name).map(JSON::valueToTree), name);
            }
            case "config" -> {
                if (method.equals("GET")) {
                    writeOr404(ex, worker.get(name).map(i -> JSON.valueToTree(i.config())), name);
                    return;
                }
                require(ex, method, "PUT");
                JsonNode body = body(ex);
                boolean existed = worker.get(name).isPresent();
                write(ex, existed ? 200 : 201, JSON.valueToTree(
                        worker.put(name, flat(body.has("config") ? body.get("config") : body))));
            }
            case "status" -> {
                require(ex, method, "GET");
                writeOr404(ex, worker.get(name).map(i -> JSON.valueToTree(i.status())), name);
            }
            case "pause" -> {
                require(ex, method, "PUT", "POST");
                writeOr404(ex, worker.pause(name).map(JSON::valueToTree), name);
            }
            case "resume" -> {
                require(ex, method, "PUT", "POST");
                writeOr404(ex, worker.resume(name).map(JSON::valueToTree), name);
            }
            case "restart" -> {
                require(ex, method, "POST", "PUT");
                writeOr404(ex, worker.restart(name).map(JSON::valueToTree), name);
            }
            case "stubs" -> {
                require(ex, method, "GET");
                writeOr404(ex, worker.stubs(name).map(JSON::valueToTree), name);
            }
            case "export" -> {
                require(ex, method, "GET");
                Optional<JsonNode> doc = worker.export(name);
                if (doc.isPresent()) {
                    ex.getResponseHeaders().set("Content-Disposition",
                            "attachment; filename=\"" + name.replaceAll("[^A-Za-z0-9._-]", "_") + "-wiremock.json\"");
                }
                writeOr404(ex, doc, name);
            }
            case "journal" -> {
                require(ex, method, "GET");
                write(ex, 200, JSON.valueToTree(worker.journal().list(name, intQuery(ex, "limit", 100))));
            }
            default -> write(ex, 404, error("recurso desconhecido em /api/mocks/bindings/" + name));
        }
    }

    private void suggestions(HttpExchange ex, String method, List<String> s) throws IOException {
        if (s.size() == 1) {
            require(ex, method, "GET");
            String execution = query(ex, "execution");
            List<MockSuggestion> list = execution == null ? advisor.suggest() : advisor.forExecution(execution);
            write(ex, 200, JSON.valueToTree(list));
            return;
        }
        Optional<MockSuggestion> suggestion = advisor.byId(s.get(1));
        if (suggestion.isEmpty()) {
            write(ex, 404, error("sugestão não encontrada (as sugestões são recalculadas a cada execução)"));
            return;
        }
        if (s.size() == 2) {
            require(ex, method, "GET");
            write(ex, 200, JSON.valueToTree(suggestion.get()));
            return;
        }
        if (s.size() == 3 && s.get(2).equals("apply")) {
            require(ex, method, "POST");
            JsonNode body = body(ex);
            List<String> variations = new ArrayList<>();
            body.path("variations").forEach(v -> variations.add(v.asText()));
            String mode = body.path("mode").asText("exclusive");
            if (!mode.equals("exclusive") && !mode.equals("on-demand")) {
                throw new IllegalArgumentException("mode deve ser exclusive ou on-demand");
            }
            Map<String, String> overrides = body.has("overrides") ? flat(body.get("overrides")) : Map.of();
            MockConnectWorker.BindingInfo info = advisor.apply(suggestion.get(), variations, mode, overrides);
            write(ex, 200, JSON.valueToTree(info));
            return;
        }
        write(ex, 404, error("recurso desconhecido em /api/mocks/suggestions"));
    }

    private ObjectNode overview() {
        ObjectNode o = JSON.createObjectNode();
        o.put("service", "Mock Connect");
        o.put("port", worker.port());
        o.put("plugins", worker.registry().plugins().size());
        o.set("bindings", JSON.valueToTree(worker.list()));
        o.set("routes", JSON.valueToTree(worker.routes()));
        o.put("journalTotal", worker.journal().count(null));
        o.put("contracts", contracts.all().size());
        o.set("warnings", JSON.valueToTree(worker.registry().warnings()));
        return o;
    }

    private static ArrayNode definitions(ConfigDef def) {
        ArrayNode list = JSON.createArrayNode();
        def.keys().values().forEach(k -> {
            ObjectNode d = list.addObject().put("name", k.name()).put("type", k.type().name()).put("required", k.required())
                    .put("importance", k.importance().name()).put("documentation", k.documentation()).put("group", k.group());
            if (!k.required() && k.defaultValue() != null) {
                d.put("defaultValue", String.valueOf(k.defaultValue()));
            }
            if (k.validator() != null && !k.validator().describe().isEmpty()) {
                d.put("validator", k.validator().describe());
            }
            d.set("recommendedValues", JSON.valueToTree(k.recommendedValues()));
        });
        return list;
    }

    /** Objeto JSON → config plano (valores não-texto viram JSON em texto, como no Connect). */
    private static Map<String, String> flat(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("config deve ser um objeto chave → valor");
        }
        node.fields().forEachRemaining(e -> out.put(e.getKey(),
                e.getValue().isNull() ? null : e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
        return out;
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new IllegalArgumentException("Content-Type deve ser application/json");
        }
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new IllegalArgumentException("corpo grande demais");
            }
            JsonNode node = JsonSupport.parse(new String(bytes, StandardCharsets.UTF_8));
            if (node == null) {
                throw new IllegalArgumentException("JSON inválido no corpo");
            }
            return node;
        }
    }

    private static void require(HttpExchange ex, String method, String... allowed) {
        for (String a : allowed) {
            if (a.equals(method)) {
                return;
            }
        }
        ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
        throw new MethodNotAllowed(String.join(", ", allowed));
    }

    /** 405 — convertido pelo handler. */
    private static final class MethodNotAllowed extends IllegalArgumentException {
        MethodNotAllowed(String allowed) {
            super("método não permitido (use " + allowed + ")");
        }
    }

    private void writeOr404(HttpExchange ex, Optional<? extends JsonNode> node, String name) throws IOException {
        if (node.isPresent()) {
            write(ex, 200, node.get());
        } else {
            write(ex, 404, error("binding não encontrado: " + name));
        }
    }

    private static ObjectNode error(String message) {
        return JSON.createObjectNode().put("error", message == null ? "erro" : message);
    }

    static void write(HttpExchange ex, int status, JsonNode node) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(node);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String query(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if ((eq >= 0 ? pair.substring(0, eq) : pair).equals(key)) {
                return URLDecoder.decode(eq >= 0 ? pair.substring(eq + 1) : "", StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int intQuery(HttpExchange ex, String key, int fallback) {
        try {
            String v = query(ex, key);
            return v == null ? fallback : Math.max(1, Math.min(500, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
