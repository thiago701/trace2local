package tech.neural7.trace2local.examples.pix.partners;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.examples.pix.infra.Json;
import tech.neural7.trace2local.otel.Trace2LocalHttp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Base dos clientes de parceiros: {@link HttpClient} do JDK instrumentado com
 * {@link Trace2LocalHttp} — cada chamada vira nó HTTP_CLIENT (zona externa na
 * Anatomia) com payloads redigidos e propagação W3C (traceparent + baggage). Com
 * {@code TRACE2LOCAL_MOCKS_ROUTING=on}, chamadas a parceiros com mock ativo no
 * Mock Connect são desviadas para o mock (o nó fica marcado como SIMULADO).
 */
abstract class PartnerHttp {

    private static final HttpClient SHARED = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String partner;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;

    PartnerHttp(String partner, String baseUrl, Duration timeout) {
        this.partner = partner;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = Trace2LocalHttp.instrument(SHARED, partner);
        this.timeout = timeout;
    }

    record Reply(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    Reply get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    Reply post(String path, JsonNode body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    private Reply send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.timeout(timeout).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 500) {
                throw new PartnerUnavailableException(partner, "HTTP " + r.statusCode(), null);
            }
            JsonNode body;
            try {
                body = Json.parse(r.body());
            } catch (IllegalArgumentException malformed) {
                throw new PartnerUnavailableException(partner, "resposta ilegível", malformed);
            }
            return new Reply(r.statusCode(), body);
        } catch (IOException e) {
            throw new PartnerUnavailableException(partner, e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PartnerUnavailableException(partner, "interrompido", e);
        }
    }

    static String enc(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
