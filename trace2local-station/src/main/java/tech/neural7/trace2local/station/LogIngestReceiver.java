package tech.neural7.trace2local.station;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.internal.LogEntryJson;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.model.LogEntry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Ingest de LOGS no modo Companion (ADR-012): {@code POST /t2lingest/v1/logs}
 * com um lote JSON {@code {"logs":[…]}} publicado pelo wrapper Lambda (ou por
 * qualquer emissor próprio). As mensagens são redigidas de novo no
 * {@code LogStore} (defesa em profundidade: a origem é externa ao processo).
 */
public final class LogIngestReceiver implements HttpHandler {

    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LINES_PER_BATCH = 5000;

    private final Trace2LocalPipeline pipeline;

    public LogIngestReceiver(Trace2LocalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                exchange.sendResponseHeaders(415, -1);
                return;
            }
            byte[] bytes;
            try (InputStream in = exchange.getRequestBody()) {
                bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (bytes.length > MAX_BODY_BYTES) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            var json = JsonSupport.MAPPER.readTree(bytes);
            int accepted = 0;
            if (json != null && json.path("logs").isArray()) {
                for (var node : json.path("logs")) {
                    if (accepted >= MAX_LINES_PER_BATCH) {
                        break;
                    }
                    LogEntry entry = LogEntryJson.fromJson(node);
                    if (entry != null) {
                        pipeline.logs().append(entry);
                        accepted++;
                    }
                }
            }
            byte[] response = ("{\"accepted\":" + accepted + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (Throwable t) {
            try {
                exchange.sendResponseHeaders(400, -1);
            } catch (IOException ignored) {
                // resposta já iniciada
            }
        } finally {
            exchange.close();
        }
    }
}
