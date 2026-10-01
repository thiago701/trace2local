package tech.neural7.trace2local.mocks.runtime;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.model.MockRequest;
import tech.neural7.trace2local.mocks.model.MockResponse;
import tech.neural7.trace2local.otel.Trace2LocalAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servidor de mocks embutido no Station (porta própria, padrão {@code 9877}):
 * {@code http://<host>:9877/<binding>/<caminho da API>}. Cada resposta leva
 * {@value Trace2LocalAttributes#MOCK_HEADER} — o nó na árvore fica marcado como simulado.
 * Requisição sem stub: 404 com <i>near-misses</i> (o que quase casou e por quê),
 * proxy para a API real ({@code unmatched=proxy}) ou 500.
 */
public final class EmbeddedMockServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EmbeddedMockServer.class.getName());
    private static final int MAX_BODY = 1024 * 1024;
    private static final long MAX_DELAY_MS = 120_000;
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "transfer-encoding", "host",
            "content-length", "upgrade", "te", "trailer", "expect", "proxy-authorization", "proxy-connection");

    private final HttpServer server;
    private final Map<String, CompiledBinding> published = new ConcurrentHashMap<>();
    private final MockJournal journal;
    private final String advertisedBase;
    private final HttpClient proxyClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public EmbeddedMockServer(String bindAddress, int port, String advertisedUrl, MockJournal journal) {
        this.journal = journal;
        try {
            this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 128);
        } catch (IOException e) {
            throw new IllegalStateException("Mock Connect: não foi possível abrir " + bindAddress + ":" + port
                    + " (TRACE2LOCAL_MOCKS_PORT) — " + e.getMessage(), e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        String adv = advertisedUrl == null || advertisedUrl.isBlank()
                ? "http://" + ("0.0.0.0".equals(bindAddress) ? "127.0.0.1" : bindAddress) + ":" + server.getAddress().getPort()
                : advertisedUrl;
        this.advertisedBase = adv.endsWith("/") ? adv.substring(0, adv.length() - 1) : adv;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** URL base anunciada do binding (o que vai para rotas e para o status). */
    public String endpointOf(String binding) {
        return advertisedBase + "/" + binding;
    }

    void publish(CompiledBinding binding) {
        binding.resetCounters();
        published.put(binding.name(), binding);
    }

    void unpublish(String binding) {
        published.remove(binding);
    }

    boolean isPublished(String binding) {
        return published.containsKey(binding);
    }

    private void handle(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        try {
            String raw = ex.getRequestURI().getRawPath();
            String[] parts = raw.substring(1).split("/", 2);
            String bindingName = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (bindingName.isEmpty()) {
                index(ex);
                return;
            }
            CompiledBinding binding = published.get(bindingName);
            if (binding == null) {
                ObjectNode err = JsonSupport.MAPPER.createObjectNode()
                        .put("error", "binding '" + bindingName + "' não está publicado (inexistente, pausado ou com falha)");
                err.set("published", JsonSupport.MAPPER.valueToTree(published.keySet()));
                writeJson(ex, 404, err, null);
                return;
            }
            String path = parts.length > 1 ? "/" + parts[1] : "/";
            MockRequest request = toRequest(ex, path);
            String traceId = MockJournal.traceIdOf(request.header("traceparent"));
            HttpExchange exchange = ex;
            CompiledBinding.Outcome outcome = binding.respond(request, req -> fetchUpstream(exchange, binding, req));
            if (outcome.stub() == null) {
                unmatched(ex, binding, request, outcome, traceId, started);
                return;
            }
            MockResponse r = outcome.response();
            String marker = "binding=" + binding.name() + "; stub=" + outcome.stub().id()
                    + (outcome.stub().passthrough() ? "; passthrough=true" : "")
                    + (outcome.applied().isEmpty() ? "" : "; variation=" + String.join(",", outcome.applied()));
            sleep(r.delayMs());
            String result = switch (r.fault()) {
                case CONNECTION_RESET, TIMEOUT -> {
                    // fecha sem resposta: o cliente vê a conexão cair (IOException/timeout)
                    yield "fault:" + r.fault().name().toLowerCase(Locale.ROOT);
                }
                case EMPTY_RESPONSE -> {
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.getResponseHeaders().set(Trace2LocalAttributes.MOCK_HEADER, marker);
                    ex.sendResponseHeaders(r.status(), 0);
                    yield "fault:empty_response";
                }
                case NONE -> {
                    r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
                    ex.getResponseHeaders().set(Trace2LocalAttributes.MOCK_HEADER, marker);
                    byte[] body = r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(r.status(), body.length == 0 ? -1 : body.length);
                    if (body.length > 0) {
                        try (OutputStream out = ex.getResponseBody()) {
                            out.write(body);
                        }
                    }
                    yield "stub";
                }
            };
            journal.record(new MockJournal.Entry(Instant.now(), binding.name(), request.method(), path,
                    r.fault() == tech.neural7.trace2local.mocks.model.Fault.NONE
                            || r.fault() == tech.neural7.trace2local.mocks.model.Fault.EMPTY_RESPONSE ? r.status() : -1,
                    outcome.stub().id(), outcome.applied(), result, traceId, elapsed(started)));
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Mock Connect: falha ao atender " + ex.getRequestURI(), e);
            try {
                writeJson(ex, 500, JsonSupport.MAPPER.createObjectNode().put("error", "falha no mock: " + e.getMessage()), null);
            } catch (IOException | RuntimeException ignored) {
                // resposta já começou ou a conexão se foi
            }
        } finally {
            ex.close();
        }
    }

    private void unmatched(HttpExchange ex, CompiledBinding binding, MockRequest request, CompiledBinding.Outcome outcome,
                           String traceId, long started) throws IOException {
        String marker = "binding=" + binding.name() + "; stub=none";
        switch (binding.unmatched()) {
            case "proxy" -> {
                int status = proxy(ex, binding, request);
                journal.record(new MockJournal.Entry(Instant.now(), binding.name(), request.method(), request.path(), status,
                        null, List.of(), status > 0 ? "proxy" : "proxy-failed", traceId, elapsed(started)));
            }
            case "error" -> {
                writeJson(ex, 500, JsonSupport.MAPPER.createObjectNode()
                        .put("error", "nenhum stub casou com " + request.method() + " " + request.path()), marker);
                journal.record(new MockJournal.Entry(Instant.now(), binding.name(), request.method(), request.path(), 500,
                        null, List.of(), "unmatched", traceId, elapsed(started)));
            }
            default -> {
                ObjectNode err = JsonSupport.MAPPER.createObjectNode()
                        .put("error", "nenhum stub casou com " + request.method() + " " + request.path())
                        .put("binding", binding.name())
                        .put("hint", "veja os near-misses abaixo ou use unmatched=proxy para cair na API real");
                ArrayNode nm = err.putArray("nearMisses");
                outcome.nearMisses().forEach(n -> nm.add(JsonSupport.MAPPER.valueToTree(n)));
                writeJson(ex, 404, err, marker);
                journal.record(new MockJournal.Entry(Instant.now(), binding.name(), request.method(), request.path(), 404,
                        null, List.of(), "unmatched", traceId, elapsed(started)));
            }
        }
    }

    /**
     * Resposta REAL do alvo para stubs de repasse ({@code source=proxy}): as variações são
     * aplicadas sobre ela. Alvo inacessível ⇒ 502 sintético (a variação ainda pode agir).
     */
    private MockResponse fetchUpstream(HttpExchange ex, CompiledBinding binding, MockRequest request) {
        String query = ex.getRequestURI().getRawQuery();
        URI target = URI.create(binding.scheme() + "://" + binding.target().hostPort() + request.path()
                + (query == null ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30))
                .method(request.method(), request.body() == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(request.body()));
        request.headers().forEach((k, vs) -> {
            if (k != null && !HOP_BY_HOP.contains(k.toLowerCase(Locale.ROOT))) {
                vs.forEach(v -> {
                    try {
                        b.header(k, v);
                    } catch (IllegalArgumentException restricted) {
                        // cabeçalho restrito do HttpClient: não repassado
                    }
                });
            }
        });
        try {
            HttpResponse<String> res = proxyClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new LinkedHashMap<>();
            res.headers().map().forEach((k, vs) -> {
                if (!HOP_BY_HOP.contains(k.toLowerCase(Locale.ROOT)) && !k.startsWith(":") && !vs.isEmpty()) {
                    headers.put(k, vs.get(0));
                }
            });
            return new MockResponse(res.statusCode(), headers, res.body(), 0, null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return MockResponse.json(502, "{\"error\":\"repasse para " + binding.target().hostPort() + " falhou: "
                    + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Proxy SÓ para o alvo declarado do binding (não é um proxy aberto). */
    private int proxy(HttpExchange ex, CompiledBinding binding, MockRequest request) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        URI target = URI.create(binding.scheme() + "://" + binding.target().hostPort() + request.path()
                + (query == null ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30))
                .method(request.method(), request.body() == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(request.body()));
        request.headers().forEach((k, vs) -> {
            if (k != null && !HOP_BY_HOP.contains(k.toLowerCase(Locale.ROOT))) {
                vs.forEach(v -> {
                    try {
                        b.header(k, v);
                    } catch (IllegalArgumentException restricted) {
                        // cabeçalho restrito do HttpClient: não repassado
                    }
                });
            }
        });
        try {
            HttpResponse<byte[]> res = proxyClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            res.headers().map().forEach((k, vs) -> {
                if (!HOP_BY_HOP.contains(k.toLowerCase(Locale.ROOT)) && !k.startsWith(":")) {
                    vs.forEach(v -> ex.getResponseHeaders().add(k, v));
                }
            });
            ex.getResponseHeaders().set(Trace2LocalAttributes.MOCK_HEADER, "binding=" + binding.name() + "; proxy=true");
            byte[] body = res.body();
            ex.sendResponseHeaders(res.statusCode(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            return res.statusCode();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            writeJson(ex, 502, JsonSupport.MAPPER.createObjectNode()
                    .put("error", "proxy para " + binding.target().hostPort() + " falhou: " + e.getClass().getSimpleName())
                    .put("hint", "a API real não está acessível daqui — adicione um stub para esta operação"),
                    "binding=" + binding.name() + "; proxy=failed");
            return -1;
        }
    }

    private void index(HttpExchange ex) throws IOException {
        ObjectNode root = JsonSupport.MAPPER.createObjectNode().put("service", "Trace2Local Mock Connect");
        ArrayNode list = root.putArray("bindings");
        published.values().forEach(b -> list.addObject().put("name", b.name()).put("target", b.target().hostPort())
                .put("endpoint", endpointOf(b.name())).put("stubs", b.stubs().size()));
        writeJson(ex, 200, root, null);
    }

    private static MockRequest toRequest(HttpExchange ex, String path) throws IOException {
        Map<String, List<String>> query = new LinkedHashMap<>();
        String rq = ex.getRequestURI().getRawQuery();
        if (rq != null) {
            for (String pair : rq.split("&")) {
                int eq = pair.indexOf('=');
                String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
                String v = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
                query.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
        String body;
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY);
            body = bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
        }
        String decodedPath = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        return new MockRequest(ex.getRequestMethod(), decodedPath, query, headers, body);
    }

    private static void writeJson(HttpExchange ex, int status, ObjectNode node, String marker) throws IOException {
        byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(node);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (marker != null) {
            ex.getResponseHeaders().set(Trace2LocalAttributes.MOCK_HEADER, marker);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(ms, MAX_DELAY_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @Override
    public void close() {
        server.stop(0);
        proxyClient.close();
    }
}
