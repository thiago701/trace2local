package tech.neural7.tracevanta.lambda;

import tech.neural7.tracevanta.internal.JsonSupport;
import tech.neural7.tracevanta.spi.MutationEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Transporte do Data Mutation Channel no modo Companion: POST em lote para
 * {@code /tvingest/v1/mutations} do Station, dentro do teto de flush da
 * invocação. Falha de rede é silenciosa — a árvore degrada sem delta, nunca
 * quebra a função.
 */
final class MutationHttpPublisher {

    private final String stationEndpoint;
    private final String stationToken;
    private final long timeoutMs;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    MutationHttpPublisher(String stationEndpoint, long timeoutMs, String stationToken) {
        this.stationEndpoint = stationEndpoint;
        this.timeoutMs = timeoutMs;
        this.stationToken = stationToken;
    }

    void publish(List<MutationEvent> batch) {
        try {
            var node = JsonSupport.MAPPER.createObjectNode();
            var array = node.putArray("mutations");
            for (MutationEvent event : batch) {
                var e = array.addObject();
                e.put("spanId", event.spanId());
                e.put("traceId", event.traceId());
                e.put("at", event.at().toString());
                e.set("mutation", JsonSupport.MAPPER.valueToTree(event.mutation()));
            }
            String endpoint = stationEndpoint.endsWith("/")
                    ? stationEndpoint + "tvingest/v1/mutations"
                    : stationEndpoint + "/tvingest/v1/mutations";
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json");
            if (stationToken != null && !stationToken.isBlank()) {
                request.header("Authorization", "Bearer " + stationToken);
            }
            client.send(request.POST(HttpRequest.BodyPublishers.ofString(
                    JsonSupport.MAPPER.writeValueAsString(node))).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Throwable ignored) {
            // best-effort: sem delta, sem drama
        }
    }
}
