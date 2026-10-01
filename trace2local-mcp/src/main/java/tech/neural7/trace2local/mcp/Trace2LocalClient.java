package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Cliente fino da API local do Trace2Local ({@code <base>/api/...}). Mutação leva
 * {@code X-Trace2Local: 1} (prova de mesma origem — ADR-015); token de UI vai como Bearer.
 * Nunca segue redirect para fora da base.
 */
public final class Trace2LocalClient {

    /** Falha da API com mensagem acionável (vira {@code isError} na ferramenta, nunca stack). */
    public static final class ApiException extends Exception {
        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final McpConfig config;
    private final HttpClient http;

    public Trace2LocalClient(McpConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public URI base() {
        return config.baseUrl();
    }

    public JsonNode get(String path) throws ApiException {
        return send("GET", path, null);
    }

    public JsonNode send(String method, String path, JsonNode body) throws ApiException {
        URI uri = URI.create(config.baseUrl() + "/api" + path);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(config.timeout())
                .header("Accept", "application/json");
        if (config.uiToken() != null) {
            b.header("Authorization", "Bearer " + config.uiToken());
        }
        if (!"GET".equals(method)) {
            b.header("X-Trace2Local", "1");
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new ApiException(0, "Trace2Local inacessível em " + config.baseUrl()
                    + " — o app (modo embedded) ou o Station está de pé? Ajuste TRACE2LOCAL_URL.");
        } catch (HttpTimeoutException e) {
            throw new ApiException(0, "Trace2Local não respondeu em " + config.timeout().toSeconds() + " s (" + path + ")");
        } catch (IOException e) {
            throw new ApiException(0, "falha de rede ao chamar o Trace2Local: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrompido");
        }
        String text = r.body() == null ? "" : r.body();
        JsonNode json = text.isBlank() ? Json.MAPPER.createObjectNode() : Json.parseOrText(text);
        if (r.statusCode() >= 400) {
            String reason = json.path("error").asText("");
            throw new ApiException(r.statusCode(), switch (r.statusCode()) {
                case 401 -> "token de UI exigido: defina TRACE2LOCAL_UI_TOKEN (perfil corporate)";
                case 404 -> reason.isBlank() ? "não encontrado: " + path : reason;
                case 421 -> "Host recusado pelo Trace2Local (allowlist): use 127.0.0.1/localhost ou TRACE2LOCAL_ALLOWED_HOSTS";
                default -> "Trace2Local respondeu " + r.statusCode() + (reason.isBlank() ? "" : " — " + reason);
            });
        }
        return json;
    }

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
