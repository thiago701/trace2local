package tech.neural7.trace2local.spring;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.internal.Redactor;
import tech.neural7.trace2local.otel.OtelAttributeNames;

import java.io.IOException;

/**
 * Span raiz de servidor HTTP sem agente: extrai o {@code traceparent} W3C,
 * abre o span SERVER e captura o request body (redigido e truncado) como
 * payload. Só é registrado quando a app NÃO tem a instrumentação Spring do
 * OTel — senão haveria dois spans raiz.
 */
public class Trace2LocalWebFilter extends OncePerRequestFilter {

    static final String ATTR_PAYLOAD_REQUEST = "t2l.payload.request";
    static final String ATTR_PAYLOAD_RESPONSE = "t2l.payload.response";

    private final Trace2LocalConfig cfg;

    public Trace2LocalWebFilter(Trace2LocalConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // resolvido a cada requisição (em testes o SDK ativo pode ser trocado)
        Tracer tracer = tech.neural7.trace2local.otel.Trace2LocalOtel.get().getTracer("tech.neural7.trace2local:http");
        Context extracted = tech.neural7.trace2local.otel.Trace2LocalOtel.get().getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), request, new HeaderGetter());
        Span span = tracer.spanBuilder(request.getMethod() + " " + request.getRequestURI())
                .setSpanKind(SpanKind.SERVER)
                .setParent(extracted)
                .setAttribute(OtelAttributeNames.HTTP_METHOD, request.getMethod())
                .setAttribute(OtelAttributeNames.HTTP_ROUTE, routeOf(request))
                .startSpan();
        // correlação de LOGS portátil: MDC com as chaves do OpenTelemetry
        // (trace_id/span_id) E do Datadog (dd.trace_id decimal 64-bit/ dd.span_id)
        // — os logs funcionam no trace local e em pipelines Datadog/OTel sem mudança
        tech.neural7.trace2local.spring.Trace2LocalLogs.injectTraceIds(span);

        // wrapper cacheia o corpo SEM consumi-lo — o controller continua lendo normalmente
        var caching = new org.springframework.web.util.ContentCachingRequestWrapper(request,
                Math.max(1024, cfg.payloadMaxBytes() * 4));
        try (Scope ignored = span.makeCurrent()) {
            chain.doFilter(caching, response);
            span.setAttribute(OtelAttributeNames.HTTP_STATUS, response.getStatus());
            if (response.getStatus() >= 400) {
                span.setStatus(StatusCode.ERROR);
            } else {
                span.setStatus(StatusCode.OK);
            }
            String body = captureBody(caching.getContentAsByteArray(), cfg);
            if (body != null) {
                span.setAttribute(ATTR_PAYLOAD_REQUEST, body);
            }
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR);
            throw t;
        } finally {
            span.end();
            tech.neural7.trace2local.spring.Trace2LocalLogs.clearTraceIds();
        }
    }

    /** Corpo redigido/truncado — capturado como atributo e movido para payload pelo processor. */
    static String captureBody(byte[] bytes, Trace2LocalConfig cfg) {
        try {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            var json = JsonSupport.MAPPER.readTree(bytes);
            if (json == null) {
                return null;
            }
            return Redactor.payloadToJson(json, cfg.payloadMaxBytes(), cfg.redactionMode());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String routeOf(HttpServletRequest request) {
        return request.getRequestURI();
    }

    private static final class HeaderGetter implements TextMapGetter<HttpServletRequest> {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return java.util.Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    }
}
