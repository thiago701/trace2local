package tech.neural7.tracevanta.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.ExecutionStore;
import tech.neural7.tracevanta.internal.JsonSupport;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;
import tech.neural7.tracevanta.spi.EndpointDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Servidor HTTP do TraceVanta (SPEC §5): REST + SSE sobre {@code com.sun.net.httpserver},
 * agnóstico de framework — usado pelo modo Embedded e pelo Station. Serve a UI
 * empacotada no WebJar, com cabeçalhos de segurança restritivos (SPEC §8.1).
 */
public final class TraceVantaHttpServer implements AutoCloseable {

    private static final String UI_ROOT = "/META-INF/resources/tracevanta/";
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final HttpServer server;
    private final TraceVantaConfig cfg;
    private final ExecutionStore store;
    private final SseHub hub;
    private final Supplier<TraceVantaMeta> meta;
    private final Supplier<List<EndpointDescriptor>> endpoints;
    private final ExecutionLauncher launcher;
    private final java.util.Map<String, com.sun.net.httpserver.HttpHandler> extraRoutes;
    /** Storytelling (descoberta de negócio por contexto/docs/engenharia reversa). */
    private final StoryService storyService = new StoryService();

    private TraceVantaHttpServer(Builder builder) {
        this.cfg = builder.cfg;
        // SPEC §8.1: loopback é obrigatório; expor fora exige a flag explícita
        if (!cfg.allowNonLoopback() && !isLoopback(cfg.bindAddress())) {
            throw new IllegalStateException(
                    "bind fora do loopback (" + cfg.bindAddress() + ") sem tracevanta.allow-non-loopback=true. "
                    + "Quem alcança a porta alcança a UI (ADR-007) — a exposição precisa ser uma decisão explícita.");
        }
        this.store = builder.pipeline.store();
        this.meta = builder.meta;
        this.endpoints = builder.endpoints;
        this.launcher = builder.launcher;
        this.extraRoutes = builder.extraRoutes;
        this.hub = new SseHub(store);
        // o hub SSE é o único consumidor dos LiveEvents do assembler (SPEC §4.4, etapa 4)
        builder.pipeline.addListener(hub::accept);
        try {
            this.server = HttpServer.create(new InetSocketAddress(cfg.bindAddress(), cfg.port()), 64);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível abrir " + cfg.bindAddress() + ":" + cfg.port(), e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        wireRoutes();
    }

    public static Builder builder(TraceVantaConfig cfg, TraceVantaPipeline pipeline) {
        return new Builder(cfg, pipeline);
    }

    public void start() {
        hub.start();
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void wireRoutes() {
        String base = cfg.basePath();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String prefix = base.isEmpty() ? "" : base;

        server.createContext(prefix + "/api/meta", this::handleMeta);
        server.createContext(prefix + "/api/endpoints", this::handleEndpoints);
        server.createContext(prefix + "/api/execute", this::handleExecute);
        server.createContext(prefix + "/api/executions", this::handleExecutions);
        server.createContext(prefix + "/api/health", this::handleHealth);
        server.createContext(prefix + "/api/stream", this::handleStream);
        server.createContext(prefix + "/", this::handleStatic);
        if (!prefix.isEmpty()) {
            server.createContext(prefix, exchange -> redirect(exchange, prefix + "/"));
        }
        // rotas extras (ex.: /v1/traces e /tvingest/v1/mutations do Station) —
        // protegidas por Bearer token quando tracevanta.station.token está definido
        extraRoutes.forEach((path, handler) -> server.createContext(path, protectIngest(handler)));
    }

    // ---------------------------------------------------------------- REST

    private void handleMeta(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        // port REAL (importante quando tracevanta.port=0): descoberta programática
        var metaNode = (com.fasterxml.jackson.databind.node.ObjectNode)
                JsonCodec.MAPPER.valueToTree(meta.get());
        metaNode.put("port", server.getAddress().getPort());
        writeJson(exchange, 200, metaNode);
    }

    private void handleEndpoints(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        List<EndpointDescriptor> list = endpoints.get() != null ? endpoints.get() : List.of();
        writeJson(exchange, 200, JsonCodec.MAPPER.valueToTree(list));
    }

    private void handleExecute(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        if (launcher == null) {
            writeJson(exchange, 400, JsonCodec.MAPPER.createObjectNode()
                    .put("error", "launcher indisponível neste modo"));
            return;
        }
        try {
            String body = readBody(exchange);
            var json = JsonSupport.MAPPER.readTree(body);
            String endpointId = json.path("endpointId").asText(null);
            if (endpointId == null || endpointId.isBlank()) {
                writeJson(exchange, 400, JsonCodec.MAPPER.createObjectNode()
                        .put("error", "endpointId é obrigatório"));
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            json.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
            Map<String, String> pathVariables = new LinkedHashMap<>();
            if (json.hasNonNull("pathVariables")) {
                json.path("pathVariables").fields().forEachRemaining(e -> pathVariables.put(e.getKey(), e.getValue().asText()));
            }
            var result = launcher.launch(new ExecutionLauncher.ExecuteRequest(
                    endpointId, headers, json.path("body"), pathVariables));
            writeJson(exchange, 202, JsonCodec.MAPPER.valueToTree(result));
        } catch (IllegalArgumentException badRequest) {
            writeJson(exchange, 400, JsonCodec.MAPPER.createObjectNode().put("error", badRequest.getMessage()));
        } catch (Exception e) {
            writeJson(exchange, 500, JsonCodec.MAPPER.createObjectNode()
                    .put("error", "falha no disparo: " + e.getMessage()));
        }
    }

    private void handleExecutions(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String base = cfg.basePath();
        String rest = path.substring(path.indexOf("/api/executions") + "/api/executions".length());

        if (method.equals("DELETE") && rest.isEmpty()) {
            store.clear();
            writeJson(exchange, 200, JsonCodec.MAPPER.createObjectNode().put("cleared", true));
            return;
        }
        if (!method.equals("GET")) {
            methodNotAllowed(exchange);
            return;
        }
        if (rest.isEmpty()) {
            String limitParam = query(exchange, "limit");
            int limit = limitParam != null ? Math.min(200, Math.max(1, Integer.parseInt(limitParam))) : 50;
            writeJson(exchange, 200, JsonCodec.MAPPER.valueToTree(store.recent(limit)));
            return;
        }
        // /api/executions/{id} ou /api/executions/{id}/export ou /api/executions/{id}/story
        String id = rest.startsWith("/") ? rest.substring(1) : rest;
        boolean export = id.endsWith("/export");
        boolean story = id.endsWith("/story");
        if (export || story) {
            id = id.substring(0, id.length() - (export ? "/export".length() : "/story".length()));
        }
        var execution = store.get(id);
        if (execution.isEmpty()) {
            writeJson(exchange, 404, JsonCodec.MAPPER.createObjectNode().put("error", "execução não encontrada"));
            return;
        }
        if (story) {
            // narrativa de negócio: intro → passos ordenados → desfecho (StoryService)
            writeJson(exchange, 200, JsonCodec.MAPPER.valueToTree(storyService.storyFor(execution.get())));
            return;
        }
        if (export) {
            var manifest = JsonCodec.MAPPER.createObjectNode();
            manifest.put("format", "tvtrace");
            manifest.put("version", TraceVantaMeta.VERSION);
            manifest.put("exportedAt", java.time.Instant.now().toString());
            var doc = JsonCodec.MAPPER.createObjectNode();
            doc.set("manifest", manifest);
            doc.set("execution", JsonCodec.MAPPER.valueToTree(execution.get()));
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + id + ".tvtrace\"");
            writeJson(exchange, 200, doc);
            return;
        }
        writeJson(exchange, 200, JsonCodec.MAPPER.valueToTree(execution.get()));
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        var health = JsonCodec.MAPPER.createObjectNode();
        health.put("status", "ok");
        health.put("dropped", store.droppedEvents());
        health.put("internalErrors", store.internalErrors());
        health.put("bufferUsage", store.bufferSize());
        health.put("liveExecutions", store.liveExecutions());
        health.put("connectedClients", hub.connectedClients());
        writeJson(exchange, 200, health);
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        applySecurityHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
        out.write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        hub.subscribe(out, () -> {
            try {
                exchange.close();
            } catch (Throwable ignored) {
                // cliente já foi embora
            }
        });
    }

    // ---------------------------------------------------------------- estáticos

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String base = cfg.basePath();
        String relative = path.startsWith(base) ? path.substring(base.length()) : path;
        relative = relative.startsWith("/") ? relative.substring(1) : relative;
        if (relative.isEmpty()) {
            relative = "index.html";
        }
        if (relative.contains("..") || relative.contains("\\")) {
            writeText(exchange, 403, "forbidden");
            return;
        }
        String resourcePath = UI_ROOT + relative;
        InputStream in = TraceVantaHttpServer.class.getResourceAsStream(resourcePath);
        if (in == null) {
            writeText(exchange, 404, "not found");
            return;
        }
        byte[] bytes;
        try (in) {
            bytes = in.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(relative));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        applySecurityHeaders(exchange);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static void redirect(HttpExchange exchange, String target) throws IOException {
        exchange.getResponseHeaders().set("Location", target);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    // ---------------------------------------------------------------- infra

    private void applySecurityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        // CSP restritivo (SPEC §8.1); a UI não usa style inline, então style-src 'self' basta
        headers.set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'");
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
    }

    /**
     * Ingest protegido (ADR-007/§8.1): com {@code tracevanta.station.token}
     * definido, OTLP e canal de mutação exigem {@code Authorization: Bearer}.
     * A UI/API de inspeção continuam locais (loopback por padrão) — o token
     * existe para a porta de ingest quando o Station é exposto.
     */
    private com.sun.net.httpserver.HttpHandler protectIngest(com.sun.net.httpserver.HttpHandler delegate) {
        String token = cfg.stationToken();
        if (token == null || token.isBlank()) {
            return delegate;
        }
        return exchange -> {
            applySecurityHeaders(exchange);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String expected = "Bearer " + token;
            if (auth == null || !java.security.MessageDigest.isEqual(
                    auth.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                writeText(exchange, 401, "unauthorized");
                return;
            }
            delegate.handle(exchange);
        };
    }

    private void writeJson(HttpExchange exchange, int status, com.fasterxml.jackson.databind.JsonNode node) throws IOException {
        byte[] bytes = JsonCodec.MAPPER.writeValueAsBytes(node);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); // execuções carregam payloads — nunca cachear
        applySecurityHeaders(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void writeText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        applySecurityHeaders(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void methodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
        writeText(exchange, 405, "method not allowed");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("corpo grande demais");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String query(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(key)) {
                return java.net.URLDecoder.decode(eq >= 0 ? pair.substring(eq + 1) : "", StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Override
    public void close() {
        hub.close();
        server.stop(0);
    }

    public static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
                || "localhost".equalsIgnoreCase(address)
                || "::1".equals(address);
    }

    // ---------------------------------------------------------------- builder

    public static final class Builder {
        private final TraceVantaConfig cfg;
        private final TraceVantaPipeline pipeline;
        private Supplier<TraceVantaMeta> meta = () -> TraceVantaMeta.embedded("tracevanta");
        private Supplier<List<EndpointDescriptor>> endpoints = List::of;
        private ExecutionLauncher launcher;
        private final Map<String, com.sun.net.httpserver.HttpHandler> extraRoutes = new LinkedHashMap<>();

        Builder(TraceVantaConfig cfg, TraceVantaPipeline pipeline) {
            this.cfg = cfg;
            this.pipeline = pipeline;
        }

        public Builder meta(Supplier<TraceVantaMeta> meta) {
            this.meta = meta;
            return this;
        }

        public Builder endpoints(Supplier<List<EndpointDescriptor>> endpoints) {
            this.endpoints = endpoints;
            return this;
        }

        public Builder launcher(ExecutionLauncher launcher) {
            this.launcher = launcher;
            return this;
        }

        /** Rota adicional no mesmo servidor (usada pelo Station para ingest OTLP/mutações). */
        public Builder extraRoute(String path, com.sun.net.httpserver.HttpHandler handler) {
            this.extraRoutes.put(path, handler);
            return this;
        }

        public TraceVantaHttpServer build() {
            return new TraceVantaHttpServer(this);
        }
    }
}
