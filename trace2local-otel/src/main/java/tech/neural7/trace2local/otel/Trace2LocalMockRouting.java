package tech.neural7.trace2local.otel;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.internal.JsonSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Roteamento do cliente para mocks: consulta {@code GET /t2lingest/v1/mock-routes}
 * do Station (cache de 2 s) e, se houver binding ATIVO para o host da chamada,
 * troca o destino pelo endpoint do mock — sem o dev mudar URL nem config.
 *
 * <p><b>Opt-in explícito</b>: só liga com {@code TRACE2LOCAL_MOCKS_ROUTING=on} e
 * {@code TRACE2LOCAL_STATION_ENDPOINT} definidos (ambiente local). Station fora do ar
 * ⇒ nenhuma rota ⇒ chamada segue para a API real (nunca falha por causa do roteamento).
 */
public final class Trace2LocalMockRouting implements MockRouter {

    private static final Logger LOG = Logger.getLogger(Trace2LocalMockRouting.class.getName());
    private static final Duration TTL = Duration.ofSeconds(2);

    private final URI routesUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    private volatile Map<String, Route> table = Map.of();
    private volatile long fetchedAt;

    public Trace2LocalMockRouting(URI stationEndpoint, String token) {
        String base = stationEndpoint.toString();
        this.routesUrl = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/t2lingest/v1/mock-routes");
        this.token = token;
    }

    /** Roteador do ambiente ou {@link MockRouter#none()} se o roteamento não foi ligado. */
    public static MockRouter fromEnv() {
        String on = env("TRACE2LOCAL_MOCKS_ROUTING");
        String endpoint = env("TRACE2LOCAL_STATION_ENDPOINT");
        if (on == null || !(on.equalsIgnoreCase("on") || on.equalsIgnoreCase("true")) || endpoint == null) {
            return MockRouter.none();
        }
        try {
            return new Trace2LocalMockRouting(URI.create(endpoint.trim()), env("TRACE2LOCAL_STATION_TOKEN"));
        } catch (IllegalArgumentException e) {
            LOG.warning("TRACE2LOCAL_STATION_ENDPOINT inválido para roteamento de mocks: " + endpoint);
            return MockRouter.none();
        }
    }

    @Override
    public Optional<Route> route(URI original) {
        if (original == null || original.getHost() == null) {
            return Optional.empty();
        }
        refreshIfStale();
        Map<String, Route> t = table;
        if (t.isEmpty()) {
            return Optional.empty();
        }
        String host = original.getHost().toLowerCase(Locale.ROOT);
        int port = original.getPort() > 0 ? original.getPort() : ("https".equalsIgnoreCase(original.getScheme()) ? 443 : 80);
        Route r = t.get(host + ":" + port);
        if (r == null) {
            r = t.get(host);
        }
        if (r == null) {
            return Optional.empty();
        }
        String base = r.target().toString();
        String path = original.getRawPath() == null ? "" : original.getRawPath();
        String query = original.getRawQuery() == null ? "" : "?" + original.getRawQuery();
        return Optional.of(new Route(URI.create(base + path + query), r.binding()));
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - fetchedAt < TTL.toMillis()) {
            return;
        }
        synchronized (this) {
            if (now - fetchedAt < TTL.toMillis()) {
                return;
            }
            fetchedAt = now;
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(routesUrl).timeout(Duration.ofSeconds(1)).GET();
                if (token != null && !token.isBlank()) {
                    b.header("Authorization", "Bearer " + token);
                }
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    table = Map.of();
                    return;
                }
                Map<String, Route> next = new LinkedHashMap<>();
                JsonNode list = JsonSupport.parse(r.body());
                if (list != null) {
                    for (JsonNode route : list) {
                        String target = route.path("target").asText("").toLowerCase(Locale.ROOT);
                        String endpoint = route.path("endpoint").asText("");
                        if (!target.isEmpty() && endpoint.startsWith("http")) {
                            String ep = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
                            next.put(target, new Route(URI.create(ep), route.path("binding").asText()));
                        }
                    }
                }
                table = Map.copyOf(next);
            } catch (Exception e) {
                // Station fora do ar: sem rotas — a chamada segue para a API real
                table = Map.of();
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            v = System.getProperty(key.toLowerCase(Locale.ROOT).replace('_', '.'));
        }
        return v == null || v.isBlank() ? null : v;
    }
}
