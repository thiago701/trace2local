package tech.neural7.trace2local.testing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.Trigger;
import tech.neural7.trace2local.otel.OtelAttributeNames;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Trace2LocalTest
class Trace2LocalAssertionsTest {

    @Test
    void assertsTreeShapeAndNegativeTwins() {
        Span root = Trace2LocalAssertions.startTestSpan("order-flow");
        Span child;
        try (Scope ignored = root.makeCurrent()) {
            child = Trace2LocalAssertions.tracer().spanBuilder("DynamoDb.PutItem").startSpan();
            child.setAttribute(OtelAttributeNames.RPC_SYSTEM, "aws-api");
            child.setAttribute(OtelAttributeNames.RPC_SERVICE, "DynamoDb");
            child.setAttribute(OtelAttributeNames.RPC_METHOD, "PutItem");
        }
        child.end();
        root.end();

        Trace2LocalAssertions.awaitLatestExecution(Duration.ofSeconds(10))
                .hasTrigger(Trigger.TEST)
                .hasNode(NodeKind.UNKNOWN, "order-flow")
                .child(NodeKind.DYNAMODB, "DynamoDB")
                .hasStatus(tech.neural7.trace2local.model.NodeStatus.OK)
                .hasAttribute(OtelAttributeNames.RPC_METHOD, "PutItem")
                // gêmeo negativo (§10): o que NÃO foi visto também é assertável
                .hasNoChild(NodeKind.SNS, "qualquer");
    }

    @Test
    void negativeAssertionFailsWhenNodeExists() {
        Span root = Trace2LocalAssertions.startTestSpan("with-sns");
        Span sns;
        try (Scope ignored = root.makeCurrent()) {
            sns = Trace2LocalAssertions.tracer().spanBuilder("Sns.Publish").startSpan();
            sns.setAttribute(OtelAttributeNames.RPC_SYSTEM, "aws-api");
            sns.setAttribute(OtelAttributeNames.RPC_SERVICE, "Sns");
        }
        sns.end();
        root.end();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        Trace2LocalAssertions.awaitLatestExecution(Duration.ofSeconds(10))
                                .doesNotHaveNode(NodeKind.SNS, "SNS"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void awaitsLatestExecutionCompletion() {
        Span root = Trace2LocalAssertions.startTestSpan("by-id");
        root.end();
        var execution = Trace2LocalAssertions.awaitLatest(Duration.ofSeconds(10));
        assertThat(execution.executionId()).startsWith("TV-");
        assertThat(execution.trigger()).isEqualTo(Trigger.TEST);
    }
}
