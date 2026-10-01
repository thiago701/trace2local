package tech.neural7.trace2local.examples.pix.notify;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.SpanContext;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tech.neural7.trace2local.examples.pix.infra.Aws;
import tech.neural7.trace2local.examples.pix.infra.Env;
import tech.neural7.trace2local.examples.pix.infra.Json;
import tech.neural7.trace2local.examples.pix.partners.NotificationClient;
import tech.neural7.trace2local.examples.pix.partners.PartnerUnavailableException;
import tech.neural7.trace2local.lambda.Trace2LocalLambdaHandler;
import tech.neural7.trace2local.lambda.Trace2LocalTraceContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static tech.neural7.trace2local.otel.Trace2LocalBusiness.step;

/**
 * {@code pix-notifier}: assina o tópico de eventos Pix (SNS) e avisa o cliente.
 * Falha do provedor de notificação NÃO desfaz o Pix: registra FAILED para reenvio
 * posterior (o nó fica vermelho na árvore, a execução segue).
 */
public final class PixNotifierHandler extends Trace2LocalLambdaHandler<Map<String, Object>, Map<String, Object>> {

    private final NotificationClient notifications = new NotificationClient();
    private final String table = Env.get("PIX_NOTIFICATIONS_TABLE", "pix-notifications");

    @Override
    protected SpanContext remoteParentOf(Map<String, Object> event, Context ctx) {
        return Trace2LocalTraceContext.fromSnsEvent(event);
    }

    @Override
    protected Baggage baggageOf(Map<String, Object> event, Context ctx) {
        return Trace2LocalTraceContext.baggageFromMessageEvent(event);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> handle(Map<String, Object> event, Context ctx) throws Exception {
        List<Map<String, Object>> records = (List<Map<String, Object>>) event.getOrDefault("Records", List.of());
        for (Map<String, Object> record : records) {
            Map<String, Object> sns = (Map<String, Object>) record.getOrDefault("Sns", Map.of());
            JsonNode envelope = Json.parse(String.valueOf(sns.get("Message")));
            String type = envelope.path("eventType").asText("PIX_EVENT");
            JsonNode data = envelope.path("data");
            String transferId = data.path("transferId").asText();
            String template = switch (type) {
                case "PIX_SETTLED" -> "pix-enviado";
                case "PIX_REJECTED" -> "pix-recusado";
                case "PIX_REVIEW_REQUIRED" -> "pix-em-analise";
                default -> "pix-atualizacao";
            };
            String status;
            String notificationId = null;
            try {
                ObjectNode payload = Json.object().put("transferId", transferId).put("amount", data.path("amount").asText());
                notificationId = step("Notificar cliente", () -> notifications.notify(data.path("payerAccountId").asText(), template, payload));
                status = "SENT";
            } catch (PartnerUnavailableException e) {
                System.out.println("WARN pix-notifier notificação de " + transferId + " pendente: " + e.getMessage());
                status = "FAILED";
            }
            String finalStatus = status;
            String finalId = notificationId;
            step("Registrar notificação", () -> {
                Aws.dynamo().putItem(r -> r.tableName(table).item(Map.of(
                        "notificationKey", AttributeValue.fromS(transferId + "#" + type),
                        "transferId", AttributeValue.fromS(transferId),
                        "eventType", AttributeValue.fromS(type),
                        "status", AttributeValue.fromS(finalStatus),
                        "providerId", AttributeValue.fromS(finalId == null ? "-" : finalId),
                        "at", AttributeValue.fromS(Instant.now().toString()))));
                return null;
            });
        }
        return Map.of("processed", records.size());
    }
}
