package tech.neural7.trace2local.examples.pix.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.SpanContext;
import tech.neural7.trace2local.examples.pix.domain.PixService;
import tech.neural7.trace2local.examples.pix.domain.Problem;
import tech.neural7.trace2local.examples.pix.domain.TransferRequest;
import tech.neural7.trace2local.examples.pix.infra.Json;
import tech.neural7.trace2local.lambda.Trace2LocalLambdaHandler;
import tech.neural7.trace2local.lambda.Trace2LocalTraceContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static tech.neural7.trace2local.otel.Trace2LocalBusiness.step;

/**
 * {@code pix-api}: integração {@code aws_proxy} do API Gateway (REST v1), contrato em
 * {@code openapi/pix-api.yaml}. Continua o trace do cliente ({@code traceparent}) e
 * aceita {@code baggage} — ex.: {@code t2l.mock=decision-denied} escolhe a variação de
 * mock do antifraude SÓ para esta requisição (Mock Connect, modo sob demanda).
 */
public final class PixApiHandler extends Trace2LocalLambdaHandler<Map<String, Object>, Map<String, Object>> {

    private final PixService service = new PixService();

    @Override
    protected SpanContext remoteParentOf(Map<String, Object> event, Context ctx) {
        return Trace2LocalTraceContext.fromApiGatewayEvent(event);
    }

    @Override
    protected Baggage baggageOf(Map<String, Object> event, Context ctx) {
        return Trace2LocalTraceContext.baggageFromApiGatewayEvent(event);
    }

    @Override
    protected Map<String, Object> handle(Map<String, Object> event, Context ctx) throws Exception {
        String method = String.valueOf(event.getOrDefault("httpMethod", "GET"));
        String resource = String.valueOf(event.getOrDefault("resource", event.getOrDefault("path", "")));
        try {
            PixService.Result r;
            if ("POST".equals(method) && resource.endsWith("/pix/transfers")) {
                TransferRequest req = step("Validar pedido", () -> TransferRequest.from(Json.parse((String) event.get("body"))));
                r = service.create(header(event, "Idempotency-Key"), req);
            } else if ("GET".equals(method) && resource.contains("/pix/transfers/")) {
                r = service.find(pathParam(event, "transferId", resource));
            } else {
                throw new Problem(404, "NOT_FOUND", "rota desconhecida: " + method + " " + resource);
            }
            System.out.println("pix-api " + method + " " + resource + " → " + r.status());
            return response(r.status(), Json.write(r.body()));
        } catch (Problem p) {
            // recusa de negócio: WARN no log (vira marcador na linha do tempo), resposta do contrato
            System.out.println("WARN pix-api recusa " + p.code() + ": " + p.getMessage());
            return response(p.status(), Json.write(PixService.problem(p.code(), p.getMessage(), p.partner())));
        } catch (IllegalArgumentException badJson) {
            return response(400, Json.write(PixService.problem("INVALID_REQUEST", badJson.getMessage(), null)));
        }
    }

    @SuppressWarnings("unchecked")
    private static String header(Map<String, Object> event, String name) {
        if (event.get("headers") instanceof Map<?, ?> h) {
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) h).entrySet()) {
                if (e.getKey() != null && name.equalsIgnoreCase(String.valueOf(e.getKey())) && e.getValue() != null) {
                    return String.valueOf(e.getValue());
                }
            }
        }
        return null;
    }

    private static String pathParam(Map<String, Object> event, String name, String resource) {
        if (event.get("pathParameters") instanceof Map<?, ?> p && p.get(name) != null) {
            return String.valueOf(p.get(name));
        }
        String path = String.valueOf(event.getOrDefault("path", resource));
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Map<String, Object> response(int status, String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", status);
        out.put("headers", Map.of("Content-Type", "application/json"));
        out.put("body", body);
        return out;
    }

    static JsonNode body(Map<String, Object> event) {
        return Json.parse((String) event.get("body"));
    }
}
