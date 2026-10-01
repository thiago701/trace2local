package tech.neural7.trace2local.lambda;

import io.opentelemetry.api.trace.SpanContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Formatos reais de propagação nos gatilhos Lambda (API Gateway, SQS, SNS). */
class Trace2LocalTraceContextTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";
    private static final String W3C = "00-" + TRACE + "-" + SPAN + "-01";

    @Test
    void parsesW3cAndAwsTraceHeader() {
        SpanContext w3c = Trace2LocalTraceContext.parse(W3C);
        assertThat(w3c.isValid()).isTrue();
        assertThat(w3c.getTraceId()).isEqualTo(TRACE);
        assertThat(w3c.getSpanId()).isEqualTo(SPAN);
        assertThat(w3c.isSampled()).isTrue();
        assertThat(w3c.isRemote()).isTrue();

        SpanContext xray = Trace2LocalTraceContext.parse("Root=1-4bf92f35-77b34da6a3ce929d0e0e4736;Parent=00F067AA0BA902B7;Sampled=0");
        assertThat(xray.getTraceId()).isEqualTo(TRACE);
        assertThat(xray.getSpanId()).isEqualTo(SPAN);
        assertThat(xray.isSampled()).isFalse();
    }

    @Test
    void malformedInputStartsANewTreeInsteadOfThrowing() {
        assertThat(Trace2LocalTraceContext.parse(null).isValid()).isFalse();
        assertThat(Trace2LocalTraceContext.parse("").isValid()).isFalse();
        assertThat(Trace2LocalTraceContext.parse("00-zz-yy-01").isValid()).isFalse();
        assertThat(Trace2LocalTraceContext.parse("00-" + TRACE + "-" + SPAN + "-zz").isValid()).isFalse();
        assertThat(Trace2LocalTraceContext.parse("Root=1-abc;Parent=123").isValid()).isFalse();
        assertThat(Trace2LocalTraceContext.parse("00-00000000000000000000000000000000-" + SPAN + "-01").isValid()).isFalse();
    }

    @Test
    void apiGatewayHeadersAreCaseInsensitiveAndFallBackToMultiValue() {
        SpanContext v1 = Trace2LocalTraceContext.fromApiGatewayEvent(Map.of("headers", Map.of("TraceParent", W3C)));
        assertThat(v1.getTraceId()).isEqualTo(TRACE);

        SpanContext xray = Trace2LocalTraceContext.fromApiGatewayEvent(Map.of(
                "headers", Map.of("X-Amzn-Trace-Id", "Root=1-4bf92f35-77b34da6a3ce929d0e0e4736;Parent=" + SPAN + ";Sampled=1")));
        assertThat(xray.getSpanId()).isEqualTo(SPAN);

        SpanContext multi = Trace2LocalTraceContext.fromApiGatewayEvent(Map.of(
                "headers", Map.of("accept", "*/*"),
                "multiValueHeaders", Map.of("traceparent", List.of(W3C))));
        assertThat(multi.getTraceId()).isEqualTo(TRACE);

        assertThat(Trace2LocalTraceContext.fromApiGatewayEvent(Map.of()).isValid()).isFalse();
    }

    @Test
    void sqsPrefersAwsTraceHeaderThenMessageAttribute() {
        Map<String, Object> viaSystem = Map.of("Records", List.of(Map.of(
                "attributes", Map.of("AWSTraceHeader", "Root=1-4bf92f35-77b34da6a3ce929d0e0e4736;Parent=" + SPAN + ";Sampled=1"))));
        assertThat(Trace2LocalTraceContext.fromSqsEvent(viaSystem).getTraceId()).isEqualTo(TRACE);

        Map<String, Object> viaAttribute = Map.of("Records", List.of(Map.of(
                "attributes", Map.of("ApproximateReceiveCount", "1"),
                "messageAttributes", Map.of("traceparent", Map.of("stringValue", W3C, "dataType", "String")))));
        assertThat(Trace2LocalTraceContext.fromSqsEvent(viaAttribute).getSpanId()).isEqualTo(SPAN);

        assertThat(Trace2LocalTraceContext.fromSqsEvent(Map.of("Records", List.of())).isValid()).isFalse();
    }

    @Test
    void snsReadsMessageAttributes() {
        Map<String, Object> event = Map.of("Records", List.of(Map.of("Sns", Map.of(
                "MessageAttributes", Map.of("traceparent", Map.of("Type", "String", "Value", W3C))))));
        assertThat(Trace2LocalTraceContext.fromSnsEvent(event).getTraceId()).isEqualTo(TRACE);
        assertThat(Trace2LocalTraceContext.fromSnsEvent(Map.of("Records", List.of(Map.of()))).isValid()).isFalse();
    }

    @Test
    void baggageCarriesTheMockVariationChosenPerRequest() {
        var fromHttp = Trace2LocalTraceContext.baggageFromApiGatewayEvent(Map.of("headers",
                Map.of("Baggage", "t2l.mock=decision-denied,tenant=x")));
        assertThat(fromHttp.getEntryValue("t2l.mock")).isEqualTo("decision-denied");
        var fromSqs = Trace2LocalTraceContext.baggageFromMessageEvent(Map.of("Records", List.of(Map.of(
                "messageAttributes", Map.of("baggage", Map.of("stringValue", "t2l.mock=http-503"))))));
        assertThat(fromSqs.getEntryValue("t2l.mock")).isEqualTo("http-503");
        assertThat(Trace2LocalTraceContext.baggageFromApiGatewayEvent(Map.of()).isEmpty()).isTrue();
    }
}
