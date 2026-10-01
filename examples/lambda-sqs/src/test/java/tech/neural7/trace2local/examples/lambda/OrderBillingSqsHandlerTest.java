package tech.neural7.trace2local.examples.lambda;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBillingSqsHandlerTest {

    @Test
    void extractsTheProducerContextFromTheSqsEventContract() {
        Map<String, Object> event = Map.of("Records", List.of(Map.of(
                "messageId", "m-1",
                "body", "{\"orderId\":\"ORDER-42\",\"customerId\":\"C-1\"}",
                "attributes", Map.of("AWSTraceHeader",
                        "Root=1-0af7651916cd43dd8448eb211c80319c;Parent=b7ad6b7169203331;Sampled=1"))));

        String header = OrderBillingSqsHandler.traceHeaderOf(event);
        var ctx = OrderBillingProcessor.parseTraceHeader(header);

        assertThat(ctx.isValid()).isTrue();
        assertThat(ctx.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(ctx.getSpanId()).isEqualTo("b7ad6b7169203331");
        assertThat(OrderBillingSqsHandler.orderIdOf((String) ((Map<?, ?>) ((List<?>) event.get("Records")).get(0)).get("body")))
                .isEqualTo("ORDER-42");
    }

    @Test
    void missingHeaderStartsANewTreeInsteadOfFailing() {
        assertThat(OrderBillingSqsHandler.traceHeaderOf(Map.of("Records", List.of(Map.of("body", "{}"))))).isNull();
        assertThat(OrderBillingSqsHandler.traceHeaderOf(Map.of())).isNull();
        assertThat(OrderBillingProcessor.parseTraceHeader(null).isValid()).isFalse();
        assertThat(OrderBillingSqsHandler.orderIdOf("{\"x\":1}")).isEqualTo("ORDER-unknown");
    }
}
