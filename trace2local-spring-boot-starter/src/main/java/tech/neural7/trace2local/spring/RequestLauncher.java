package tech.neural7.trace2local.spring;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.context.Scope;
import tech.neural7.trace2local.internal.ExecutionIds;
import tech.neural7.trace2local.otel.Trace2LocalAttributes;
import tech.neural7.trace2local.server.ExecutionLauncher;
import tech.neural7.trace2local.spi.EndpointDescriptor;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Request Launcher (SPEC §4.9 + §8.2): dispara a requisição COM o traceparent do
 * Trace2Local, para a execução aparecer na árvore em menos de um segundo.
 *
 * <p>O Trace2Local é, literalmente, um proxy que envia requisições por ordem do
 * navegador — então o alvo NUNCA vem do cliente: é resolvido a partir do
 * {@code endpointId} do catálogo, restrito a loopback e à porta da própria
 * aplicação, sem redirects e com a resolução DNS re-verificada (defesa contra
 * rebinding).
 */
public final class RequestLauncher implements ExecutionLauncher {

    private final Supplier<List<EndpointDescriptor>> catalog;
    private final int appPort;
    private final String appHost;
    private final HttpClient client;

    public RequestLauncher(Supplier<List<EndpointDescriptor>> catalog, int appPort) {
        this(catalog, appPort, "127.0.0.1");
    }

    public RequestLauncher(Supplier<List<EndpointDescriptor>> catalog, int appPort, String appHost) {
        this.catalog = catalog;
        this.appPort = appPort;
        this.appHost = appHost;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public LaunchResult launch(ExecuteRequest request) throws Exception {
        EndpointDescriptor endpoint = catalog.get().stream()
                .filter(e -> e.endpointId().equals(request.endpointId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "endpointId desconhecido: " + request.endpointId() + " — o alvo vem do catálogo, nunca do cliente (SPEC §8.2)"));

        String path = resolvePath(endpoint.path(), request.pathVariables());
        String url = "http://" + appHost + ":" + appPort + path;
        assertLoopbackTarget(appHost);

        // contexto W3C novo, atribuível ao clique (SPEC §4.9)
        String executionId = ExecutionIds.next();
        var otel = tech.neural7.trace2local.otel.Trace2LocalOtel.get();
        var tracer = otel.getTracer("tech.neural7.trace2local:launcher");
        Span span = tracer.spanBuilder("UI Dispatch: " + endpoint.method() + " " + endpoint.path())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(Trace2LocalAttributes.EXECUTION_ID, executionId)
                .setAttribute(Trace2LocalAttributes.TRIGGER, Trace2LocalAttributes.TRIGGER_UI)
                .startSpan();

        String traceId;
        try (Scope ignored = span.makeCurrent()) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60));
            // 1) headers do cliente primeiro — os de trace são sobrescritos abaixo (SPEC §8.2/§4.9)
            request.headers().forEach(builder::header);
            builder.header("Content-Type", "application/json");
            // 2) contexto W3C do Trace2Local por cima: traceparent/tracestate/baggage SEMPRE nossos,
            //    nunca do cliente (o clique é atribuível ao disparo)
            io.opentelemetry.api.baggage.Baggage baggage = io.opentelemetry.api.baggage.Baggage.builder()
                    .put("trace2local.trigger", Trace2LocalAttributes.TRIGGER_UI)
                    .build();
            Context context = Context.current().with(span).with(baggage);
            otel.getPropagators().getTextMapPropagator()
                    .inject(context, builder, HttpRequestBuilderSetter.INSTANCE);
            if (request.body() != null && !request.body().isNull()) {
                builder.method(endpoint.method(), HttpRequest.BodyPublishers.ofString(request.body().toString()));
            } else {
                builder.method(endpoint.method(), HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            span.setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.HTTP_STATUS, response.statusCode());
            span.setStatus(response.statusCode() >= 400 ? StatusCode.ERROR : StatusCode.OK);
            traceId = span.getSpanContext().getTraceId();
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR);
            traceId = span.getSpanContext().getTraceId();
            if (t instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(t);
        } finally {
            span.end();
        }
        return new LaunchResult(executionId, traceId);
    }

    static String resolvePath(String template, Map<String, String> pathVariables) {
        String path = template;
        if (pathVariables != null) {
            for (Map.Entry<String, String> e : pathVariables.entrySet()) {
                // codifica o valor: "ORDER#88291" não pode virar fragmento de URL
                String encoded = java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                path = path.replace("{" + e.getKey() + "}", encoded);
            }
        }
        return path;
    }

    private static void assertLoopbackTarget(String host) throws Exception {
        InetAddress resolved = InetAddress.getByName(host);
        if (!resolved.isLoopbackAddress()) {
            // re-validação da resolução (defesa contra rebinding — SPEC §8.2)
            throw new IllegalArgumentException("alvo fora do loopback: " + host);
        }
    }

    private enum HttpRequestBuilderSetter implements TextMapSetter<HttpRequest.Builder> {
        INSTANCE;

        @Override
        public void set(HttpRequest.Builder carrier, String key, String value) {
            carrier.header(key, value);
        }
    }
}
