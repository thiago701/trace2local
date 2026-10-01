package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * Transporte <i>Streamable HTTP</i> do MCP, mínimo e seguro, para harnesses que preferem URL a
 * processo: um endpoint {@code POST /mcp} que responde {@code application/json} (sem stream SSE —
 * nenhuma ferramenta emite progresso). Notificação → {@code 202}. {@code GET}/{@code DELETE} → 405.
 *
 * <p>Endurecimento (recomendações de segurança da especificação): bind SÓ em 127.0.0.1, validação
 * de {@code Origin} (anti DNS rebinding — só loopback), Bearer opcional
 * ({@code TRACE2LOCAL_MCP_HTTP_TOKEN}) comparado em tempo constante, corpo limitado a 1 MiB.
 */
final class HttpTransport {

    private static final int MAX_BODY = 1 << 20;

    private final McpServer server;
    private final McpConfig config;
    private HttpServer http;

    HttpTransport(McpServer server, McpConfig config) {
        this.server = server;
        this.config = config;
    }

    int start(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/mcp", this::handle);
        http.start();
        return http.getAddress().getPort();
    }

    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (origin != null && !loopbackOrigin(origin)) {
                send(ex, 403, "{\"error\":\"Origin não permitido\"}");
                return;
            }
            if (config.httpToken() != null) {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String presented = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
                if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), config.httpToken().getBytes(StandardCharsets.UTF_8))) {
                    ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    send(ex, 401, "{\"error\":\"token exigido\"}");
                    return;
                }
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "POST");
                send(ex, 405, "{\"error\":\"use POST (este servidor não abre stream SSE)\"}");
                return;
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readNBytes(MAX_BODY + 1);
            }
            if (body.length > MAX_BODY) {
                send(ex, 413, "{\"error\":\"corpo acima de 1 MiB\"}");
                return;
            }
            JsonNode response;
            try {
                response = server.handle(Json.parse(new String(body, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                response = McpServer.error(null, -32700, "JSON inválido");
            }
            if (response == null) {
                ex.sendResponseHeaders(202, -1);
                return;
            }
            ex.getResponseHeaders().set("MCP-Protocol-Version", server.negotiatedVersion());
            send(ex, 200, Json.write(response));
        } finally {
            ex.close();
        }
    }

    private static boolean loopbackOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && McpConfig.isLoopback(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
