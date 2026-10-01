package tech.neural7.trace2local.aws;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import tech.neural7.trace2local.otel.Trace2LocalOtel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contexto de trace (e baggage) como atributos de mensagem SQS/SNS — a continuidade
 * produtor → fila/tópico → consumidor sem depender do {@code AWSTraceHeader}
 * (que exige X-Ray/propagação do SDK e nem todo emulador repassa):
 *
 * <pre>{@code
 * sqs.sendMessage(r -> r.queueUrl(url).messageBody(json)
 *         .messageAttributes(Trace2LocalMessaging.sqsAttributes()));
 * sns.publish(r -> r.topicArn(arn).message(json)
 *         .messageAttributes(Trace2LocalMessaging.snsAttributes()));
 * }</pre>
 *
 * No consumidor: {@code Trace2LocalTraceContext.fromSqsEvent/fromSnsEvent} (trace) e
 * {@code baggageFromMessageEvent} (baggage — ex.: variação de mock escolhida na entrada).
 */
public final class Trace2LocalMessaging {

    private Trace2LocalMessaging() {}

    /** {@code traceparent}/{@code tracestate}/{@code baggage} do contexto corrente. */
    public static Map<String, String> currentCarrier() {
        Map<String, String> carrier = new LinkedHashMap<>();
        Trace2LocalOtel.get().getPropagators().getTextMapPropagator()
                .inject(Context.current(), carrier, MapSetter.INSTANCE);
        return carrier;
    }

    public static Map<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue> sqsAttributes() {
        Map<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue> out = new LinkedHashMap<>();
        currentCarrier().forEach((k, v) -> out.put(k, software.amazon.awssdk.services.sqs.model.MessageAttributeValue
                .builder().dataType("String").stringValue(v).build()));
        return out;
    }

    public static Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> snsAttributes() {
        Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> out = new LinkedHashMap<>();
        currentCarrier().forEach((k, v) -> out.put(k, software.amazon.awssdk.services.sns.model.MessageAttributeValue
                .builder().dataType("String").stringValue(v).build()));
        return out;
    }

    private enum MapSetter implements TextMapSetter<Map<String, String>> {
        INSTANCE;

        @Override
        public void set(Map<String, String> carrier, String key, String value) {
            if (carrier != null && key != null && value != null) {
                carrier.put(key, value);
            }
        }
    }
}
