package tech.neural7.trace2local.lambda;

import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.model.LogEntry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Transporte dos LOGS da invocação no modo Companion (ADR-012): POST em lote
 * para {@code /t2lingest/v1/logs} do Station, dentro do teto de flush. Mesmo
 * contrato de falha do {@link MutationHttpPublisher}: rede caída = linha do tempo
 * sem logs, nunca função quebrada.
 */
final class LogHttpPublisher {

    private final String endpoint;
    private final String stationToken;
    private final long timeoutMs;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    LogHttpPublisher(String stationEndpoint, long timeoutMs, String stationToken) {
        this.endpoint = stationEndpoint.endsWith("/")
                ? stationEndpoint + "t2lingest/v1/logs"
                : stationEndpoint + "/t2lingest/v1/logs";
        this.timeoutMs = timeoutMs;
        this.stationToken = stationToken;
    }

    void publish(List<LogEntry> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            var node = JsonSupport.MAPPER.createObjectNode();
            var array = node.putArray("logs");
            for (LogEntry entry : batch) {
                array.add(tech.neural7.trace2local.internal.LogEntryJson.toJson(entry));
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(50, timeoutMs)))
                    .header("Content-Type", "application/json");
            if (stationToken != null && !stationToken.isBlank()) {
                request.header("Authorization", "Bearer " + stationToken);
            }
            client.send(request.POST(HttpRequest.BodyPublishers.ofString(
                            JsonSupport.MAPPER.writeValueAsString(node))).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Throwable ignored) {
            // best-effort: sem logs, sem drama
        }
    }
}
