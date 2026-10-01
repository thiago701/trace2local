package tech.neural7.trace2local.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.ExecutionStore;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.spi.EndpointDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servidor HTTP do Trace2Local (SPEC §5): REST + SSE sobre {@code com.sun.net.httpserver},
 * agnóstico de framework — usado pelo modo Embedded e pelo Station. Serve a UI
 * empacotada no WebJar, com cabeçalhos de segurança restritivos (SPEC §8.1) e o
 * {@link RequestGuard} (Host allowlist anti-DNS-rebinding, prova de mesma origem
 * em mutações, token de UI opcional).
 */
public final class Trace2LocalHttpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Trace2LocalHttpServer.class.getName());
    private static final String UI_ROOT = "/META-INF/resources/trace2local/";
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    /** CSP sem inline (SPEC §8.1) — endurecida para o perfil corporativo. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    private final HttpServer server;
    private final Trace2LocalConfig cfg;
    private final ExecutionStore store;
    private final Trace2LocalPipeline pipeline;
    private final SseHub hub;
    private final Supplier<Trace2LocalMeta> meta;
    private final Supplier<List<EndpointDescriptor>> endpoints;
    private final ExecutionLauncher launcher;
    private final Map<String, HttpHandler> extraRoutes;
    private final Map<String, HttpHandler> apiRoutes;
    private final RequestGuard guard;
    private final BusinessGlossary glossary = new BusinessGlossary();
    /** Storytelling (descoberta de negócio por contexto/docs/engenharia reversa). */
    private final StoryService storyService = new StoryService(glossary);
    /** Catálogo de infra/DevOps (URLs, ARNs, envs, Terraform — com fonte e usos). */
    private final InfraService infraService;
    /** Inteligência: Regras Assíncronas Preditivas + assistente técnico/executivo (ADR-011/013). */
    private final PredictiveService predictive;

    private Trace2LocalHttpServer(Builder builder) {
        this.cfg = builder.cfg;
        // SPEC §8.1: loopback é obrigatório; expor fora exige a flag explícita
        if (!cfg.allowNonLoopback() && !isLoopback(cfg.bindAddress())) {
            throw new IllegalStateException(
                    "bind fora do loopback (" + cfg.bindAddress() + ") sem trace2local.allow-non-loopback=true. "
                    + "Quem alcança a porta alcança a UI (ADR-007) — a exposição precisa ser uma decisão explícita.");
        }
        this.pipeline = builder.pipeline;
        this.store = builder.pipeline.store();
        this.meta = builder.meta;
        this.endpoints = builder.endpoints;
        this.launcher = builder.launcher;
        this.extraRoutes = builder.extraRoutes;
        this.apiRoutes = builder.apiRoutes;
        this.guard = new RequestGuard(cfg);
        this.infraService = new InfraService(builder.pipeline.store(), cfg);
        this.hub = new SseHub(store);
        this.predictive = new PredictiveService(store, builder.pipeline.logs(), cfg, glossary, infraService, hub);
        // o hub SSE e o pipeline preditivo consomem os LiveEvents do assembler (SPEC §4.4, etapa 4);
        // o preditivo só faz offer() numa fila limitada — o assembler nunca espera análise
        builder.pipeline.addListener(hub::accept);
        builder.pipeline.addListener(predictive::onLiveEvent);
        try {
            this.server = HttpServer.create(new InetSocketAddress(cfg.bindAddress(), cfg.port()), 64);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível abrir " + cfg.bindAddress() + ":" + cfg.port(), e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        wireRoutes();
    }

    public static Builder builder(Trace2LocalConfig cfg, Trace2LocalPipeline pipeline) {
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

        server.createContext(prefix + "/api/meta", guarded(this::handleMeta));
        server.createContext(prefix + "/api/endpoints", guarded(this::handleEndpoints));
        server.createContext(prefix + "/api/execute", guarded(this::handleExecute));
        server.createContext(prefix + "/api/executions", guarded(this::handleExecutions));
        server.createContext(prefix + "/api/health", guarded(this::handleHealth));
        server.createContext(prefix + "/api/infra", guarded(this::handleInfra));
        server.createContext(prefix + "/api/stream", guarded(this::handleStream));
        server.createContext(prefix + "/api/topology", guarded(this::handleTopology));
        server.createContext(prefix + "/api/insights", guarded(this::handleInsights));
        server.createContext(prefix + "/api/intelligence", guarded(this::handleIntelligence));
        server.createContext(prefix + "/api/history", guarded(this::handleHistory));
        // extensões da API da UI (ex.: /api/mocks do Mock Connect — ADR-016): MESMO RequestGuard
        // e cabeçalhos de segurança das rotas nativas
        apiRoutes.forEach((sub, handler) -> server.createContext(prefix + "/api/" + sub, guarded(exchange -> {
            applySecurityHeaders(exchange);
            handler.handle(exchange);
        })));
        server.createContext(prefix + "/", guarded(this::handleStatic));
        if (!prefix.isEmpty()) {
            server.createContext(prefix, guarded(exchange -> redirect(exchange, prefix + "/")));
        }
        // rotas extras (ex.: /v1/traces, /t2lingest/v1/mutations, /t2lingest/v1/logs do Station) —
        // protegidas por Bearer token quando trace2local.station.token está definido
        extraRoutes.forEach((path, handler) -> server.createContext(path, protectIngest(handler)));
    }

    /** Envolve a rota com o {@link RequestGuard} e converte exceção em 500 SEM detalhe interno. */
    private HttpHandler guarded(HttpHandler delegate) {
        return exchange -> {
            try {
                RequestGuard.Verdict verdict = guard.check(exchange);
                if (verdict != null) {
                    if (verdict.status() == 401 && guard.exchangeTokenForCookie(exchange)) {
                        return;
                    }
                    writeJson(exchange, verdict.status(), JsonCodec.MAPPER.createObjectNode().put("error", verdict.reason()));
                    return;
                }
                delegate.handle(exchange);
            } catch (IOException io) {
                throw io;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "falha interna na rota " + exchange.getRequestURI().getPath(), t);
                try {
                    writeJson(exchange, 500, JsonCodec.MAPPER.createObjectNode().put("error", "erro interno do Trace2Local"));
                } catch (Throwable ignored) {
                    exchange.close();
                }
            }
        };
    }

    // ---------------------------------------------------------------- REST

    private void handleMeta(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        // port REAL (importante quando trace2local.port=0): descoberta programática
        var metaNode = (com.fasterxml.jackson.databind.node.ObjectNode)
                JsonCodec.MAPPER.valueToTree(meta.get());
        metaNode.put("port", server.getAddress().getPort());
        metaNode.put("uiTokenRequired", guard.tokenRequired());
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
        if (!isJson(exchange)) {
            writeJson(exchange, 415, JsonCodec.MAPPER.createObjectNode().put("error", "Content-Type deve ser application/json"));
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
            writeJson(exchange, 400, JsonCodec.MAPPER.createObjectNode().put("error", safeMessage(badRequest)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException badJson) {
            writeJson(exchange, 400, JsonCodec.MAPPER.createObjectNode().put("error", "JSON inválido no corpo"));
        } catch (Exception e) {
            LOG.log(Level.FINE, "falha no disparo", e);
            writeJson(exchange, 502, JsonCodec.MAPPER.createObjectNode()
                    .put("error", "falha no disparo (" + e.getClass().getSimpleName() + ") — detalhes no log da aplicação"));
        }
    }

    private void handleExecutions(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String rest = path.substring(path.indexOf("/api/executions") + "/api/executions".length());

        if (method.equals("DELETE") && rest.isEmpty()) {
            store.clear();
            pipeline.logs().clear();
            predictive.clear();
            writeJson(exchange, 200, JsonCodec.MAPPER.createObjectNode().put("cleared", true));
            return;
        }
        if (!method.equals("GET")) {
            methodNotAllowed(exchange);
            return;
        }
        if (rest.isEmpty() || rest.equals("/")) {
            int limit = intQuery(exchange, "limit", 50, 1, 200);
            writeJson(exchange, 200, JsonCodec.MAPPER.valueToTree(store.recent(limit)));
            return;
        }
        // /api/executions/{id}[/export|/story|/logs|/insights]
        String id = rest.startsWith("/") ? rest.substring(1) : rest;
        String sub = "";
        int slash = id.indexOf('/');
        if (slash >= 0) {
            sub = id.substring(slash + 1);
            id = id.substring(0, slash);
        }
        Optional<Execution> execution = store.get(id);
        if (execution.isEmpty()) {
            writeJson(exchange, 404, JsonCodec.MAPPER.createObjectNode().put("error", "execução não encontrada"));
            return;
        }
        switch (sub) {
            case "" -> writeJson(exchange, 200, JsonCodec.MAPPER.valueToTree(execution.get()));
            // narrativa de negócio: intro → passos ordenados → desfecho (StoryService)
            case "story" -> writeJson(exchange, 200, JsonCodec.MAPPER.valueToTree(storyService.storyFor(execution.get())));
            // linha do tempo estilo CloudWatch: logs correlacionados por trace/RequestId (ADR-012)
            case "logs" -> writeJson(exchange, 200, predictive.logsJson(execution.get()));
            // assistente técnico + executivo + insights preditivos (ADR-011/ADR-013)
            case "insights" -> writeJson(exchange, 200, predictive.assist(execution.get()));
            case "export" -> {
                var manifest = JsonCodec.MAPPER.createObjectNode();
                manifest.put("format", "tvtrace");
                manifest.put("version", Trace2LocalMeta.VERSION);
                manifest.put("exportedAt", java.time.Instant.now().toString());
                var doc = JsonCodec.MAPPER.createObjectNode();
                doc.set("manifest", manifest);
                doc.set("execution", JsonCodec.MAPPER.valueToTree(execution.get()));
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + id.replaceAll("[^A-Za-z0-9._-]", "_") + ".tvtrace\"");
                writeJson(exchange, 200, doc);
            }
            default -> writeJson(exchange, 404, JsonCodec.MAPPER.createObjectNode().put("error", "recurso desconhecido"));
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        var health = JsonCodec.MAPPER.createObjectNode();
        health.put("status", "ok");
        health.put("dropped", store.droppedEvents());
        health.put("internalErrors", store.internalErrors());
        health.put("bufferUsage", store.bufferSize());
        health.put("liveExecutions", store.liveExecutions());
        health.put("connectedClients", hub.connectedClients());
        health.put("logLines", pipeline.logs().total());
        writeJson(exchange, 200, health);
    }

    /** Catálogo de infra/DevOps: URLs, ARNs, envs e recursos Terraform com fonte e usos. */
    private void handleInfra(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        writeJson(exchange, 200, infraService.snapshot());
    }

    /** Anatomia do ecossistema (componentes por zona, arestas, recursos declarados). */
    private void handleTopology(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        writeJson(exchange, 200, predictive.topology());
    }

    /**
     * Insights preditivos: {@code GET /api/insights} (top global),
     * {@code GET /api/insights/{fp}/explain?llm=1}, {@code POST /api/insights/{fp}/feedback}.
     */
    private void handleInsights(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rest = path.substring(path.indexOf("/api/insights") + "/api/insights".length());
        String method = exchange.getRequestMethod();
        if (rest.isEmpty() || rest.equals("/")) {
            if (!"GET".equals(method)) {
                methodNotAllowed(exchange);
                return;
            }
            writeJson(exchange, 200, predictive.topInsights(intQuery(exchange, "limit", 12, 1, 100)));
            return;
        }
        String[] parts = rest.substring(1).split("/", 2);
        String fingerprint = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        String action = parts.length > 1 ? parts[1] : "";
        if ("explain".equals(action) && ("GET".equals(method) || "POST".equals(method))) {
            // GET = explicação por template LOCAL (sem egress). LLM (egress + custo) só via
            // POST — que passa pela prova de mesma origem do RequestGuard: uma página
            // de terceiros não consegue disparar a chamada externa com um <img src>.
            boolean llm = false;
            if ("POST".equals(method)) {
                if (!isJson(exchange)) {
                    writeJson(exchange, 415, JsonCodec.MAPPER.createObjectNode().put("error", "Content-Type deve ser application/json"));
                    return;
                }
                llm = JsonSupport.MAPPER.readTree(readBody(exchange)).path("llm").asBoolean(false);
            }
            var json = predictive.explain(fingerprint, llm);
            if (json == null) {
                writeJson(exchange, 404, JsonCodec.MAPPER.createObjectNode().put("error", "insight não encontrado"));
            } else {
                writeJson(exchange, 200, json);
            }
            return;
        }
        if ("feedback".equals(action) && "POST".equals(method)) {
            if (!isJson(exchange)) {
                writeJson(exchange, 415, JsonCodec.MAPPER.createObjectNode().put("error", "Content-Type deve ser application/json"));
                return;
            }
            String act = JsonSupport.MAPPER.readTree(readBody(exchange)).path("action").asText("");
            boolean ok = predictive.feedback(fingerprint, act);
            writeJson(exchange, ok ? 200 : 400, JsonCodec.MAPPER.createObjectNode().put("ok", ok));
            return;
        }
        methodNotAllowed(exchange);
    }

    private void handleIntelligence(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        writeJson(exchange, 200, predictive.status());
    }

    private void handleHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        writeJson(exchange, 200, predictive.history(query(exchange, "flow")));
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        if (!hub.hasCapacity()) {
            writeJson(exchange, 503, JsonCodec.MAPPER.createObjectNode()
                    .put("error", "limite de " + SseHub.MAX_SUBSCRIBERS + " conexões SSE atingido"));
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
        if (relative.contains("..") || relative.contains("\\") || relative.contains("\0")) {
            writeText(exchange, 403, "forbidden");
            return;
        }
        String resourcePath = UI_ROOT + relative;
        InputStream in = Trace2LocalHttpServer.class.getResourceAsStream(resourcePath);
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
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
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
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        headers.set("X-Permitted-Cross-Domain-Policies", "none");
    }

    /**
     * Ingest protegido (ADR-007/§8.1): com {@code trace2local.station.token}
     * definido, OTLP e canais de mutação/logs exigem {@code Authorization: Bearer}.
     * A UI/API de inspeção continuam locais (loopback por padrão) — o token
     * existe para a porta de ingest quando o Station é exposto.
     */
    private HttpHandler protectIngest(HttpHandler delegate) {
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

    private static boolean isJson(HttpExchange exchange) {
        String ct = exchange.getRequestHeaders().getFirst("Content-Type");
        return ct != null && ct.toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage() == null ? "requisição inválida" : t.getMessage();
        return m.length() > 200 ? m.substring(0, 200) : m;
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

    private static int intQuery(HttpExchange exchange, String key, int fallback, int min, int max) {
        String raw = query(exchange, key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.min(max, Math.max(min, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return fallback;
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
        predictive.close();
        server.stop(0);
    }

    public static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
                || "localhost".equalsIgnoreCase(address)
                || "::1".equals(address);
    }

    // ---------------------------------------------------------------- builder

    public static final class Builder {
        private final Trace2LocalConfig cfg;
        private final Trace2LocalPipeline pipeline;
        private Supplier<Trace2LocalMeta> meta = () -> Trace2LocalMeta.embedded("trace2local");
        private Supplier<List<EndpointDescriptor>> endpoints = List::of;
        private ExecutionLauncher launcher;
        private final Map<String, HttpHandler> extraRoutes = new LinkedHashMap<>();
        private final Map<String, HttpHandler> apiRoutes = new LinkedHashMap<>();

        Builder(Trace2LocalConfig cfg, Trace2LocalPipeline pipeline) {
            this.cfg = cfg;
            this.pipeline = pipeline;
        }

        public Builder meta(Supplier<Trace2LocalMeta> meta) {
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

        /** Rota adicional no mesmo servidor (usada pelo Station para ingest OTLP/mutações/logs). */
        public Builder extraRoute(String path, HttpHandler handler) {
            this.extraRoutes.put(path, handler);
            return this;
        }

        /**
         * Extensão da API da UI em {@code /api/<sub>} — protegida pelo {@code RequestGuard}
         * (Host allowlist, prova de mesma origem em mutações, token de UI).
         */
        public Builder apiRoute(String sub, HttpHandler handler) {
            if (sub == null || !sub.matches("[a-z][a-z0-9-]*")) {
                throw new IllegalArgumentException("sub-rota inválida: " + sub);
            }
            this.apiRoutes.put(sub, handler);
            return this;
        }

        public Trace2LocalHttpServer build() {
            return new Trace2LocalHttpServer(this);
        }
    }
}
