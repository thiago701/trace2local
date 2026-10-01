package tech.neural7.trace2local.lambda;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Continuidade de trace para os gatilhos Lambda mais comuns — sem copiar parser em
 * cada handler. Use no {@code remoteParentOf} do {@link Trace2LocalLambdaHandler}:
 *
 * <pre>{@code
 * protected SpanContext remoteParentOf(Map<String, Object> event, Context ctx) {
 *     return Trace2LocalTraceContext.fromApiGatewayEvent(event);   // ou fromSqsEvent / fromSnsEvent
 * }
 * }</pre>
 *
 * Formatos aceitos: W3C {@code traceparent} ({@code 00-<trace>-<span>-<flags>}) e
 * {@code AWSTraceHeader}/{@code X-Amzn-Trace-Id} ({@code Root=1-…;Parent=…;Sampled=1}).
 * Entrada ilegível devolve {@link SpanContext#getInvalid()} — nova árvore, nunca exceção.
 */
public final class Trace2LocalTraceContext {

    private Trace2LocalTraceContext() {}

    /** Interpreta {@code traceparent} ou {@code AWSTraceHeader}. */
    public static SpanContext parse(String header) {
        if (header == null || header.isBlank()) {
            return SpanContext.getInvalid();
        }
        String h = header.trim();
        try {
            if (h.startsWith("00-")) {
                String[] p = h.split("-");
                if (p.length >= 4 && p[1].length() == 32 && p[2].length() == 16) {
                    boolean sampled = (Integer.parseInt(p[3].substring(0, 2), 16) & 1) == 1;
                    return SpanContext.createFromRemoteParent(p[1], p[2],
                            sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(), TraceState.getDefault());
                }
                return SpanContext.getInvalid();
            }
            String traceId = null;
            String parent = null;
            boolean sampled = true;
            for (String part : h.split(";")) {
                String kv = part.trim();
                if (kv.startsWith("Root=")) {
                    String v = kv.substring(5);
                    // "1-5759e988-bd862e3fe1be46a994272793" (X-Ray) ou "1-<32 hex>" → 32 hex
                    traceId = (v.startsWith("1-") ? v.substring(2) : v).replace("-", "").toLowerCase(Locale.ROOT);
                } else if (kv.startsWith("Parent=")) {
                    parent = kv.substring(7).toLowerCase(Locale.ROOT);
                } else if (kv.startsWith("Sampled=")) {
                    sampled = !"0".equals(kv.substring(8));
                }
            }
            if (traceId == null || parent == null || traceId.length() != 32 || parent.length() != 16) {
                return SpanContext.getInvalid();
            }
            return SpanContext.createFromRemoteParent(traceId, parent,
                    sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(), TraceState.getDefault());
        } catch (RuntimeException malformed) {
            return SpanContext.getInvalid();
        }
    }

    /** Cabeçalhos HTTP (case-insensitive): {@code traceparent}, depois {@code X-Amzn-Trace-Id}. */
    public static SpanContext fromHeaders(Map<String, ?> headers) {
        if (headers == null) {
            return SpanContext.getInvalid();
        }
        String w3c = null;
        String xray = null;
        for (Map.Entry<String, ?> e : headers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String k = e.getKey().toLowerCase(Locale.ROOT);
            Object v = e.getValue() instanceof List<?> list && !list.isEmpty() ? list.get(0) : e.getValue();
            if ("traceparent".equals(k)) {
                w3c = String.valueOf(v);
            } else if ("x-amzn-trace-id".equals(k)) {
                xray = String.valueOf(v);
            }
        }
        SpanContext ctx = parse(w3c);
        return ctx.isValid() ? ctx : parse(xray);
    }

    /** Evento do API Gateway (REST v1 ou HTTP v2): {@code headers}/{@code multiValueHeaders}. */
    @SuppressWarnings("unchecked")
    public static SpanContext fromApiGatewayEvent(Map<String, Object> event) {
        if (event == null) {
            return SpanContext.getInvalid();
        }
        Object headers = event.get("headers");
        SpanContext ctx = headers instanceof Map<?, ?> m ? fromHeaders((Map<String, ?>) m) : SpanContext.getInvalid();
        if (!ctx.isValid() && event.get("multiValueHeaders") instanceof Map<?, ?> mv) {
            ctx = fromHeaders((Map<String, ?>) mv);
        }
        return ctx;
    }

    /** Evento SQS (event source mapping): {@code Records[0].attributes.AWSTraceHeader} ou atributo {@code traceparent}. */
    public static SpanContext fromSqsEvent(Map<String, Object> event) {
        Map<?, ?> rec = firstRecord(event);
        if (rec == null) {
            return SpanContext.getInvalid();
        }
        if (rec.get("attributes") instanceof Map<?, ?> attrs && attrs.get("AWSTraceHeader") != null) {
            SpanContext ctx = parse(String.valueOf(attrs.get("AWSTraceHeader")));
            if (ctx.isValid()) {
                return ctx;
            }
        }
        if (rec.get("messageAttributes") instanceof Map<?, ?> ma && ma.get("traceparent") instanceof Map<?, ?> tp) {
            return parse(String.valueOf(tp.get("stringValue")));
        }
        return SpanContext.getInvalid();
    }

    /** Evento SNS → Lambda: {@code Records[0].Sns.MessageAttributes.traceparent.Value}. */
    public static SpanContext fromSnsEvent(Map<String, Object> event) {
        Map<?, ?> rec = firstRecord(event);
        if (rec == null || !(rec.get("Sns") instanceof Map<?, ?> sns)) {
            return SpanContext.getInvalid();
        }
        if (sns.get("MessageAttributes") instanceof Map<?, ?> ma) {
            for (String key : new String[] {"traceparent", "AWSTraceHeader"}) {
                if (ma.get(key) instanceof Map<?, ?> attr) {
                    SpanContext ctx = parse(String.valueOf(attr.get("Value")));
                    if (ctx.isValid()) {
                        return ctx;
                    }
                }
            }
        }
        return SpanContext.getInvalid();
    }

    /** Baggage W3C ({@code baggage}) dos cabeçalhos — vazio se ausente/ilegível. */
    public static io.opentelemetry.api.baggage.Baggage baggageFromHeaders(Map<String, ?> headers) {
        if (headers == null) {
            return io.opentelemetry.api.baggage.Baggage.empty();
        }
        String value = null;
        for (Map.Entry<String, ?> e : headers.entrySet()) {
            if (e.getKey() != null && "baggage".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
                Object v = e.getValue() instanceof List<?> list && !list.isEmpty() ? list.get(0) : e.getValue();
                value = String.valueOf(v);
            }
        }
        return parseBaggage(value);
    }

    /** Baggage do evento do API Gateway ({@code headers}, depois {@code multiValueHeaders}). */
    @SuppressWarnings("unchecked")
    public static io.opentelemetry.api.baggage.Baggage baggageFromApiGatewayEvent(Map<String, Object> event) {
        if (event == null) {
            return io.opentelemetry.api.baggage.Baggage.empty();
        }
        io.opentelemetry.api.baggage.Baggage b = event.get("headers") instanceof Map<?, ?> m
                ? baggageFromHeaders((Map<String, ?>) m) : io.opentelemetry.api.baggage.Baggage.empty();
        if (b.isEmpty() && event.get("multiValueHeaders") instanceof Map<?, ?> mv) {
            b = baggageFromHeaders((Map<String, ?>) mv);
        }
        return b;
    }

    /** Baggage do atributo de mensagem {@code baggage} (SQS: stringValue; SNS: Value). */
    public static io.opentelemetry.api.baggage.Baggage baggageFromMessageEvent(Map<String, Object> event) {
        Map<?, ?> rec = firstRecord(event);
        if (rec == null) {
            return io.opentelemetry.api.baggage.Baggage.empty();
        }
        if (rec.get("messageAttributes") instanceof Map<?, ?> ma && ma.get("baggage") instanceof Map<?, ?> b) {
            return parseBaggage(String.valueOf(b.get("stringValue")));
        }
        if (rec.get("Sns") instanceof Map<?, ?> sns && sns.get("MessageAttributes") instanceof Map<?, ?> ma
                && ma.get("baggage") instanceof Map<?, ?> b) {
            return parseBaggage(String.valueOf(b.get("Value")));
        }
        return io.opentelemetry.api.baggage.Baggage.empty();
    }

    private static io.opentelemetry.api.baggage.Baggage parseBaggage(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return io.opentelemetry.api.baggage.Baggage.empty();
        }
        io.opentelemetry.context.Context ctx = io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator.getInstance()
                .extract(io.opentelemetry.context.Context.root(), value, new io.opentelemetry.context.propagation.TextMapGetter<String>() {
                    @Override
                    public Iterable<String> keys(String carrier) {
                        return List.of("baggage");
                    }

                    @Override
                    public String get(String carrier, String key) {
                        return "baggage".equals(key) ? carrier : null;
                    }
                });
        return io.opentelemetry.api.baggage.Baggage.fromContext(ctx);
    }

    private static Map<?, ?> firstRecord(Map<String, Object> event) {
        if (event != null && event.get("Records") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            return m;
        }
        return null;
    }
}
