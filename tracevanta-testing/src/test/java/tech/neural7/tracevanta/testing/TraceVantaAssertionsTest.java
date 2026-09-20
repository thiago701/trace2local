package tech.neural7.tracevanta.testing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.model.NodeKind;
import tech.neural7.tracevanta.model.Trigger;
import tech.neural7.tracevanta.otel.OtelAttributeNames;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@TraceVantaTest
class TraceVantaAssertionsTest {

    @Test
    void assertsTreeShapeAndNegativeTwins() {
        Span root = TraceVantaAssertions.startTestSpan("order-flow");
        Span child;
        try (Scope ignored = root.makeCurrent()) {
            child = TraceVantaAssertions.tracer().spanBuilder("DynamoDb.PutItem").startSpan();
            child.setAttribute(OtelAttributeNames.RPC_SYSTEM, "aws-api");
            child.setAttribute(OtelAttributeNames.RPC_SERVICE, "DynamoDb");
            child.setAttribute(OtelAttributeNames.RPC_METHOD, "PutItem");
        }
        child.end();
        root.end();

        TraceVantaAssertions.awaitLatestExecution(Duration.ofSeconds(10))
                .hasTrigger(Trigger.TEST)
                .hasNode(NodeKind.UNKNOWN, "order-flow")
                .child(NodeKind.DYNAMODB, "DynamoDB")
                .hasStatus(tech.neural7.tracevanta.model.NodeStatus.OK)
                .hasAttribute(OtelAttributeNames.RPC_METHOD, "PutItem")
                // gêmeo negativo (§10): o que NÃO foi visto também é assertável
                .hasNoChild(NodeKind.SNS, "qualquer");
    }

    @Test
    void negativeAssertionFailsWhenNodeExists() {
        Span root = TraceVantaAssertions.startTestSpan("with-sns");
        Span sns;
        try (Scope ignored = root.makeCurrent()) {
            sns = TraceVantaAssertions.tracer().spanBuilder("Sns.Publish").startSpan();
            sns.setAttribute(OtelAttributeNames.RPC_SYSTEM, "aws-api");
            sns.setAttribute(OtelAttributeNames.RPC_SERVICE, "Sns");
        }
        sns.end();
        root.end();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        TraceVantaAssertions.awaitLatestExecution(Duration.ofSeconds(10))
                                .doesNotHaveNode(NodeKind.SNS, "SNS"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void awaitsLatestExecutionCompletion() {
        Span root = TraceVantaAssertions.startTestSpan("by-id");
        root.end();
        var execution = TraceVantaAssertions.awaitLatest(Duration.ofSeconds(10));
        assertThat(execution.executionId()).startsWith("TV-");
        assertThat(execution.trigger()).isEqualTo(Trigger.TEST);
    }
}
