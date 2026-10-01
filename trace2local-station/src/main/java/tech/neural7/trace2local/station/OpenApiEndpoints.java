package tech.neural7.trace2local.station;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.openapi.OpenApiDocument;
import tech.neural7.trace2local.server.ExecutionLauncher;
import tech.neural7.trace2local.spi.EndpointDescriptor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Aba API no modo Companion: o catálogo vem do CONTRATO OpenAPI do serviço
 * ({@code TRACE2LOCAL_OPENAPI_SPEC}) e o disparo vai para a URL real
 * ({@code TRACE2LOCAL_OPENAPI_BASE_URL} — ex.: o API Gateway do LocalStack). Cada disparo
 * leva um {@code traceparent} novo: a UI acompanha a execução pelo traceId (o id da
 * execução só nasce dentro da Lambda). Cabeçalho obrigatório de idempotência é gerado.
 */
final class OpenApiEndpoints implements ExecutionLauncher {

    private static final Logger LOG = Logger.getLogger(OpenApiEndpoints.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenApiDocument doc;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private OpenApiEndpoints(OpenApiDocument doc, String baseUrl) {
        this.doc = doc;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** {@code null} se as variáveis não estiverem definidas ou o contrato for ilegível (o Station sobe mesmo assim). */
    static OpenApiEndpoints fromEnv() {
        String spec = System.getenv("TRACE2LOCAL_OPENAPI_SPEC");
        if (spec == null || spec.isBlank()) {
            return null;
        }
        try {
            OpenApiDocument doc = OpenApiDocument.load(Path.of(spec));
            String base = System.getenv("TRACE2LOCAL_OPENAPI_BASE_URL");
            if (base == null || base.isBlank()) {
                base = doc.serverUrls().stream().filter(u -> u.startsWith("http") && !u.contains("{")).findFirst().orElse(null);
            }
            if (base == null) {
                LOG.warning("TRACE2LOCAL_OPENAPI_SPEC sem URL utilizável: defina TRACE2LOCAL_OPENAPI_BASE_URL");
                return null;
            }
            LOG.info("API do serviço a partir do contrato " + doc.title() + " → " + base);
            return new OpenApiEndpoints(doc, base);
        } catch (Exception e) {
            LOG.warning("contrato OpenAPI ilegível (" + spec + "): " + e.getMessage());
            return null;
        }
    }

    List<EndpointDescriptor> endpoints() {
        List<EndpointDescriptor> out = new ArrayList<>();
        for (OpenApiDocument.Operation op : doc.operations()) {
            JsonNode example = doc.requestExample(op);
            List<EndpointDescriptor.Parameter> params = doc.parameters(op).stream()
                    .map(p -> new EndpointDescriptor.Parameter(p.name(), p.in(), p.required(),
                            p.example() != null ? p.example() : isIdempotency(p.name()) ? "(gerado a cada disparo)" : null,
                            p.description()))
                    .toList();
            out.add(new EndpointDescriptor(op.label(), op.method(), op.path(),
                    op.summary() != null ? op.summary() : doc.title(), doc.requestSchema(op),
                    example == null ? null : JsonSupport.writePretty(example), params));
        }
        return out;
    }

    @Override
    public LaunchResult launch(ExecuteRequest request) {
        OpenApiDocument.Operation op = doc.operations().stream()
                .filter(o -> o.label().equals(request.endpointId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("endpoint desconhecido: " + request.endpointId()));
        String path = op.path();
        Map<String, String> vars = request.pathVariables() == null ? Map.of() : request.pathVariables();
        Map<String, String> given = request.headers() == null ? Map.of() : request.headers();
        HttpRequest.Builder b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(60));
        StringBuilder query = new StringBuilder();
        for (OpenApiDocument.Param p : doc.parameters(op)) {
            String value = "path".equals(p.in()) ? vars.get(p.name()) : given.get(p.name());
            if ((value == null || value.isBlank() || value.startsWith("(gerado")) && isIdempotency(p.name())) {
                value = "ui-" + HexFormat.of().formatHex(bytes(8));
            }
            if (value == null || value.isBlank()) {
                value = p.example();
            }
            if (value == null || value.isBlank()) {
                if (p.required()) {
                    throw new IllegalArgumentException("parâmetro obrigatório ausente: " + p.name() + " (" + p.in() + ")");
                }
                continue;
            }
            switch (p.in()) {
                case "path" -> path = path.replace("{" + p.name() + "}", URLEncoder.encode(value, StandardCharsets.UTF_8));
                case "header" -> b.header(p.name(), value);
                case "query" -> query.append(query.isEmpty() ? "?" : "&").append(p.name()).append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                default -> { /* cookie: não suportado */ }
            }
        }
        given.forEach((k, v) -> {
            if (doc.parameters(op).stream().noneMatch(p -> p.name().equalsIgnoreCase(k)) && v != null && !v.isBlank()) {
                b.header(k, v);
            }
        });
        String traceId = HexFormat.of().formatHex(bytes(16));
        b.header("traceparent", "00-" + traceId + "-" + HexFormat.of().formatHex(bytes(8)) + "-01");
        JsonNode body = request.body();
        boolean hasBody = body != null && !body.isNull() && !body.isMissingNode();
        if (hasBody) {
            b.header("Content-Type", "application/json");
        }
        b.uri(URI.create(baseUrl + path + query)).method(op.method().toUpperCase(Locale.ROOT),
                hasBody ? HttpRequest.BodyPublishers.ofString(JsonSupport.write(body)) : HttpRequest.BodyPublishers.noBody());
        http.sendAsync(b.build(), HttpResponse.BodyHandlers.discarding())
                .whenComplete((r, e) -> LOG.fine(() -> "disparo " + op.key() + " → " + (r != null ? r.statusCode() : e)));
        // o id da execução nasce na Lambda: a UI segue a árvore pelo traceId
        return new LaunchResult(null, traceId);
    }

    private static boolean isIdempotency(String name) {
        return name.toLowerCase(Locale.ROOT).contains("idempot");
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
