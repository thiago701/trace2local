package tech.neural7.trace2local.examples.pix.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import tech.neural7.trace2local.aws.Trace2LocalMessaging;
import tech.neural7.trace2local.examples.pix.infra.Aws;
import tech.neural7.trace2local.examples.pix.infra.Env;
import tech.neural7.trace2local.examples.pix.infra.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mensageria: fila de liquidação (SQS) e eventos de domínio (SNS). O contexto de
 * trace e o baggage viajam como atributos de mensagem — a liquidação e a notificação
 * aparecem como continuação da MESMA árvore no Station (ADR-014).
 */
public final class PixEvents {

    private final String settlementQueueUrl = Env.get("PIX_SETTLEMENT_QUEUE_URL", "");
    private final String eventsTopicArn = Env.get("PIX_EVENTS_TOPIC_ARN", "");

    public void requestSettlement(ObjectNode message) {
        Aws.sqs().sendMessage(r -> r.queueUrl(settlementQueueUrl).messageBody(Json.write(message))
                .messageAttributes(Trace2LocalMessaging.sqsAttributes()));
    }

    public void publish(String eventType, ObjectNode payload) {
        Map<String, MessageAttributeValue> attrs = new LinkedHashMap<>(Trace2LocalMessaging.snsAttributes());
        attrs.put("eventType", MessageAttributeValue.builder().dataType("String").stringValue(eventType).build());
        ObjectNode envelope = Json.object().put("eventType", eventType);
        envelope.set("data", payload);
        Aws.sns().publish(r -> r.topicArn(eventsTopicArn).message(Json.write(envelope)).messageAttributes(attrs));
    }
}
