package tech.neural7.trace2local.lambda;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import tech.neural7.trace2local.otel.OtelAttributeNames;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O GATILHO da invocação na raiz da árvore: "pix-api · POST /pix/transfers → 502" em vez
 * de só "pix-api". Reconhece os eventos em forma de {@code Map} (o que o runtime entrega
 * a handlers {@code RequestHandler<Map<String,Object>, …>}):
 *
 * <ul>
 *   <li>API Gateway REST (v1) e HTTP API (v2): método, rota e status HTTP da resposta
 *       ({@code statusCode} ≥ 500 marca a raiz em vermelho; 4xx é recusa de negócio, não erro);</li>
 *   <li>SQS (event source mapping): fila e número de tentativas (reentrega visível);</li>
 *   <li>SNS: tópico e tipo do evento (atributo {@code eventType}, se houver).</li>
 * </ul>
 *
 * Nada de payload entra aqui — só rótulos estruturais (ADR-007).
 */
public final class LambdaTriggerSemantics {

    /** Nome do span raiz + atributos de entrada. */
    public record Entry(String spanName, Map<String, String> attributes, boolean http) {}

    private LambdaTriggerSemantics() {}

    public static Entry of(Object input, String functionName) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (!(input instanceof Map<?, ?> event)) {
            return new Entry(functionName, attrs, false);
        }
        Object method = event.get("httpMethod");
        Object requestContext = event.get("requestContext");
        if (method == null && requestContext instanceof Map<?, ?> rc && rc.get("http") instanceof Map<?, ?> http) {
            method = http.get("method"); // HTTP API (v2)
        }
        if (method != null) {
            Object resource = event.get("resource");
            Object routeKey = event.get("routeKey");
            Object path = event.get("path");
            String route = resource != null ? String.valueOf(resource)
                    : routeKey != null ? routeOfV2(String.valueOf(routeKey))
                    : path != null ? String.valueOf(path) : "/";
            attrs.put(OtelAttributeNames.HTTP_METHOD, String.valueOf(method));
            attrs.put(OtelAttributeNames.HTTP_ROUTE, route);
            return new Entry(functionName + " · " + method + " " + route, attrs, true);
        }
        if (event.get("Records") instanceof List<?> records && !records.isEmpty() && records.get(0) instanceof Map<?, ?> r0) {
            Object source = r0.get("eventSource") != null ? r0.get("eventSource") : r0.get("EventSource");
            if ("aws:sqs".equals(source)) {
                String queue = lastSegment(String.valueOf(r0.get("eventSourceARN")));
                Object attempt = r0.get("attributes") instanceof Map<?, ?> a ? a.get("ApproximateReceiveCount") : null;
                String suffix = attempt != null && !"1".equals(String.valueOf(attempt)) ? " (tentativa " + attempt + ")" : "";
                return new Entry(functionName + " · SQS " + queue + suffix, attrs, false);
            }
            if ("aws:sns".equals(source) && r0.get("Sns") instanceof Map<?, ?> sns) {
                String topic = lastSegment(String.valueOf(sns.get("TopicArn")));
                String type = sns.get("MessageAttributes") instanceof Map<?, ?> ma && ma.get("eventType") instanceof Map<?, ?> et
                        ? " " + et.get("Value") : "";
                return new Entry(functionName + " · SNS " + topic + type, attrs, false);
            }
        }
        return new Entry(functionName, attrs, false);
    }

    /** Status HTTP da resposta do API Gateway na raiz; 5xx = erro (4xx é decisão de negócio). */
    static void onResult(Entry entry, Span span, Object result) {
        if (entry == null || !entry.http() || !(result instanceof Map<?, ?> response)) {
            return;
        }
        Object status = response.get("statusCode");
        if (status instanceof Number n) {
            span.setAttribute(OtelAttributeNames.HTTP_STATUS, n.longValue());
            if (n.intValue() >= 500) {
                span.setStatus(StatusCode.ERROR, "HTTP " + n.intValue());
            }
        }
    }

    private static String routeOfV2(String routeKey) {
        int space = routeKey.indexOf(' ');
        return space > 0 ? routeKey.substring(space + 1) : routeKey;
    }

    private static String lastSegment(String arn) {
        int i = Math.max(arn.lastIndexOf(':'), arn.lastIndexOf('/'));
        return i >= 0 ? arn.substring(i + 1) : arn;
    }
}
