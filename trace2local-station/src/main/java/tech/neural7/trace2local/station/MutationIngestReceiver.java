package tech.neural7.trace2local.station;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.spi.MutationEvent;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/**
 * Ingest do Data Mutation Channel no modo Companion (SPEC §5.3): OTLP não tem
 * equivalente para delta de dados, então o Station aceita
 * {@code POST /t2lingest/v1/mutations} com um lote JSON publicado pelo modo
 * Lambda (ou por qualquer instrumentação própria).
 */
public final class MutationIngestReceiver implements HttpHandler {

    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private final Trace2LocalPipeline pipeline;

    public MutationIngestReceiver(Trace2LocalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            var json = JsonSupport.MAPPER.readTree(bytes);
            int accepted = 0;
            if (json.has("mutations") && json.get("mutations").isArray()) {
                for (var node : json.get("mutations")) {
                    try {
                        String spanId = node.path("spanId").asText();
                        String traceId = node.path("traceId").asText();
                        DataMutation mutation = JsonSupport.MAPPER.treeToValue(node.path("mutation"), DataMutation.class);
                        Instant at = node.hasNonNull("at") ? Instant.parse(node.path("at").asText()) : Instant.now();
                        if (!spanId.isBlank() && !traceId.isBlank()) {
                            pipeline.buffer().offer(new MutationEvent(spanId, traceId, mutation, at));
                            accepted++;
                        }
                    } catch (Throwable ignored) {
                        // item ilegível é pulado; o lote continua
                    }
                }
            }
            byte[] response = ("{\"accepted\":" + accepted + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (Throwable t) {
            exchange.sendResponseHeaders(400, -1);
        } finally {
            exchange.close();
        }
    }
}
