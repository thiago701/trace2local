package tech.neural7.trace2local.otel;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import tech.neural7.trace2local.config.RedactionMode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.internal.Redactor;
import tech.neural7.trace2local.internal.TextRedactor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Instrumentação das chamadas de SAÍDA feitas com {@link java.net.http.HttpClient}
 * — as APIs externas do seu sistema (parceiros, BACEN, antifraude, gateways).
 * Troca de uma linha, sem agente:
 *
 * <pre>{@code
 * HttpClient dict = Trace2LocalHttp.instrument(HttpClient.newHttpClient(), "DICT (BACEN)");
 * }</pre>
 *
 * Cada {@code send}/{@code sendAsync} vira um nó {@code HTTP_CLIENT} na árvore com:
 * método, URL (sem credenciais, query redigida), host/porta (o host decide a ZONA
 * na Anatomia: loopback/LocalStack = fronteira local, demais = fronteira externa),
 * status, erro (4xx/5xx/exceção, conforme a convenção OTel para spans CLIENT) e os
 * payloads de requisição e resposta — <b>redigidos e truncados na origem</b> (ADR-007).
 * O contexto W3C ({@code traceparent}) é propagado: se o parceiro também usar
 * Trace2Local/OTel, a árvore continua do outro lado.
 */
public final class Trace2LocalHttp {

    /**
     * Opções da instrumentação.
     *
     * @param mockRouter desvio para mocks do Mock Connect (ADR-016); o padrão lê
     *                   {@code TRACE2LOCAL_MOCKS_ROUTING=on} — desligado sem essa variável
     */
    public record Options(String peerService, int payloadMaxBytes, RedactionMode redaction, boolean capturePayload,
                          MockRouter mockRouter) {
        public Options {
            mockRouter = mockRouter == null ? MockRouter.none() : mockRouter;
        }

        public static Options defaults() {
            return new Options(null, 8192, RedactionMode.STRICT, true, Trace2LocalMockRouting.fromEnv());
        }

        public Options withPeerService(String name) {
            return new Options(name, payloadMaxBytes, redaction, capturePayload, mockRouter);
        }

        public Options withMockRouter(MockRouter router) {
            return new Options(peerService, payloadMaxBytes, redaction, capturePayload, router);
        }
    }

    private static final String TRACER = "tech.neural7.trace2local:http-client";
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "^(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|.*@.*|(?=.*\\d)[A-Za-z0-9_+.:=-]{6,})$");

    private Trace2LocalHttp() {}

    public static HttpClient instrument(HttpClient delegate) {
        return instrument(delegate, Options.defaults());
    }

    /** {@code peerService}: nome lógico do parceiro, exibido no rótulo do nó. */
    public static HttpClient instrument(HttpClient delegate, String peerService) {
        return instrument(delegate, Options.defaults().withPeerService(peerService));
    }

    public static HttpClient instrument(HttpClient delegate, Options options) {
        if (delegate instanceof Traced) {
            return delegate;
        }
        return new Traced(delegate, options == null ? Options.defaults() : options);
    }

    /**
     * Caminho com ids trocados por {@code {id}} — rótulo estável, sem dado pessoal
     * (CPF, e-mail e chaves Pix costumam viajar no path).
     */
    static String routeOf(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            String decoded = java.net.URLDecoder.decode(seg, StandardCharsets.UTF_8);
            sb.append('/').append(ID_SEGMENT.matcher(decoded).matches() ? "{id}" : seg);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /** URL para o atributo: sem userinfo, valores da query redigidos. */
    static String safeUrl(URI uri) {
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() > 0) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(routeOf(uri));
        String q = uri.getRawQuery();
        if (q != null && !q.isEmpty()) {
            sb.append('?');
            String[] pairs = q.split("&");
            for (int i = 0; i < pairs.length; i++) {
                int eq = pairs[i].indexOf('=');
                sb.append(i == 0 ? "" : "&").append(eq > 0 ? pairs[i].substring(0, eq) + "=…" : pairs[i]);
            }
        }
        return sb.toString();
    }

    static String capture(String body, Options o) {
        if (body == null || body.isEmpty() || !o.capturePayload()) {
            return null;
        }
        String trimmed = body.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode node = JsonSupport.MAPPER.readTree(trimmed);
                return Redactor.payloadToJson(node, o.payloadMaxBytes(), o.redaction());
            } catch (IOException | RuntimeException notJson) {
                // segue como texto
            }
        }
        String red = o.redaction() == RedactionMode.OFF ? trimmed : TextRedactor.redact(trimmed);
        return red.length() > o.payloadMaxBytes() ? red.substring(0, o.payloadMaxBytes()) + Redactor.TRUNCATED : red;
    }

    // ------------------------------------------------------------------ cliente decorado

    static final class Traced extends HttpClient {
        private final HttpClient delegate;
        private final Options options;

        Traced(HttpClient delegate, Options options) {
            this.delegate = delegate;
            this.options = options;
        }

        private record Call(Span span, HttpRequest request, TeePublisher tee) {}

        private Call start(HttpRequest original) {
            URI uri = original.uri();
            String host = uri.getHost() == null ? "?" : uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            String who = options.peerService() != null && !options.peerService().isBlank() ? options.peerService() : host;
            Span span = Trace2LocalOtel.get().getTracer(TRACER)
                    .spanBuilder(who + " " + routeOf(uri))
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(OtelAttributeNames.HTTP_METHOD, original.method())
                    .setAttribute(OtelAttributeNames.URL_FULL, safeUrl(uri))
                    .setAttribute(OtelAttributeNames.SERVER_ADDRESS, host)
                    .setAttribute(OtelAttributeNames.SERVER_PORT, (long) port)
                    .startSpan();
            if (options.peerService() != null) {
                span.setAttribute(OtelAttributeNames.PEER_SERVICE, options.peerService());
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(original, (name, value) ->
                    !"traceparent".equalsIgnoreCase(name) && !"tracestate".equalsIgnoreCase(name));
            Optional<MockRouter.Route> route;
            try {
                route = options.mockRouter().route(uri);
            } catch (RuntimeException e) {
                route = Optional.empty(); // roteamento nunca derruba a chamada
            }
            if (route.isPresent()) {
                // o nó mantém o host ORIGINAL (a dependência lógica) e ganha a marca de simulado
                b.uri(route.get().target());
                span.setAttribute(Trace2LocalAttributes.MOCK_ROUTED, true);
                span.setAttribute(Trace2LocalAttributes.MOCK, "binding=" + route.get().binding() + "; routed=true");
            }
            TeePublisher tee = null;
            Optional<HttpRequest.BodyPublisher> body = original.bodyPublisher();
            if (body.isPresent() && options.capturePayload()) {
                tee = new TeePublisher(body.get(), options.payloadMaxBytes() * 2);
                b.method(original.method(), tee);
            }
            Trace2LocalOtel.get().getPropagators().getTextMapPropagator()
                    .inject(Context.current().with(span), b, Setter.INSTANCE);
            return new Call(span, b.build(), tee);
        }

        private <T> void finish(Call call, HttpResponse<T> response, Throwable error) {
            Span span = call.span();
            try {
                if (call.tee() != null) {
                    String req = capture(call.tee().captured(), options);
                    if (req != null) {
                        span.setAttribute(Trace2LocalAttributes.PAYLOAD_REQUEST, req);
                    }
                }
                if (response != null) {
                    int status = response.statusCode();
                    span.setAttribute(OtelAttributeNames.HTTP_STATUS, (long) status);
                    response.headers().firstValue(Trace2LocalAttributes.MOCK_HEADER).ifPresent(marker -> span.setAttribute(
                            Trace2LocalAttributes.MOCK, marker.length() > 200 ? marker.substring(0, 200) : marker));
                    Object body = response.body();
                    String text = body instanceof String s ? s
                            : body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : null;
                    String res = capture(text, options);
                    if (res != null) {
                        span.setAttribute(Trace2LocalAttributes.PAYLOAD_RESPONSE, res);
                    }
                    if (status >= 400) {
                        span.setStatus(StatusCode.ERROR, "HTTP " + status);
                    }
                }
                if (error != null) {
                    span.recordException(error);
                    span.setStatus(StatusCode.ERROR, String.valueOf(error));
                }
            } catch (RuntimeException ignored) {
                // instrumentação nunca quebra a chamada do dev
            } finally {
                span.end();
            }
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            Call call = start(request);
            try (Scope ignored = call.span().makeCurrent()) {
                HttpResponse<T> response = delegate.send(call.request(), handler);
                finish(call, response, null);
                return response;
            } catch (IOException | InterruptedException | RuntimeException e) {
                finish(call, null, e);
                throw e;
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            Call call = start(request);
            return delegate.sendAsync(call.request(), handler).whenComplete((r, e) -> finish(call, r, e));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> push) {
            Call call = start(request);
            return delegate.sendAsync(call.request(), handler, push).whenComplete((r, e) -> finish(call, r, e));
        }

        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
        @Override public java.net.http.WebSocket.Builder newWebSocketBuilder() { return delegate.newWebSocketBuilder(); }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public boolean awaitTermination(Duration duration) throws InterruptedException { return delegate.awaitTermination(duration); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public void shutdownNow() { delegate.shutdownNow(); }
        @Override public void close() { delegate.close(); }
    }

    /** Copia (até um teto) os bytes do corpo enquanto eles seguem para a rede. */
    static final class TeePublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final int max;
        private final ByteArrayOutputStream copy = new ByteArrayOutputStream();

        TeePublisher(HttpRequest.BodyPublisher delegate, int max) {
            this.delegate = delegate;
            this.max = max;
        }

        String captured() {
            synchronized (copy) {
                return copy.size() == 0 ? null : copy.toString(StandardCharsets.UTF_8);
            }
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { subscriber.onSubscribe(s); }
                @Override public void onNext(ByteBuffer item) {
                    synchronized (copy) {
                        ByteBuffer d = item.duplicate();
                        int n = Math.min(d.remaining(), Math.max(0, max - copy.size()));
                        if (n > 0) {
                            byte[] b = new byte[n];
                            d.get(b);
                            copy.write(b, 0, n);
                        }
                    }
                    subscriber.onNext(item);
                }
                @Override public void onError(Throwable t) { subscriber.onError(t); }
                @Override public void onComplete() { subscriber.onComplete(); }
            });
        }
    }

    private enum Setter implements TextMapSetter<HttpRequest.Builder> {
        INSTANCE;

        @Override
        public void set(HttpRequest.Builder carrier, String key, String value) {
            if (carrier != null) {
                carrier.setHeader(key, value);
            }
        }
    }
}
