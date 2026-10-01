package tech.neural7.trace2local.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.internal.ExecutionJson;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.ErrorInfo;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionMetrics;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.FieldDelta;
import tech.neural7.trace2local.model.MutationFidelity;
import tech.neural7.trace2local.model.MutationKind;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;
import tech.neural7.trace2local.model.Payload;
import tech.neural7.trace2local.model.Trigger;
import tech.neural7.trace2local.model.Warning;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O contrato REST/.tvtrace volta a ser {@link Execution} sem perda (base dos datasets de traces reais). */
class ExecutionJsonRoundTripTest {

    @Test
    void restContractRoundTripsToTheSameExecution() {
        Instant t0 = Instant.parse("2026-10-01T02:17:06.865Z");
        var f = JsonNodeFactory.instance;
        DataMutation mutation = new DataMutation(MutationKind.UPDATE, "orders", "ORDER-C1",
                f.objectNode().put("pk", "ORDER-C1").put("total", 99.9),
                f.objectNode().put("pk", "ORDER-C1").put("total", 99.9).put("status", "BILLED"),
                List.of(new FieldDelta("status", NullNode.getInstance(), f.textNode("BILLED"))), MutationFidelity.EXACT);
        Node ddb = new Node("c-ddb", "c-root", NodeKind.DYNAMODB, "DynamoDB: orders", NodeStatus.OK, t0.plusMillis(5010),
                Duration.ofMillis(31), Duration.ofMillis(31), Map.of(tech.neural7.trace2local.otel.OtelAttributeNames.DB_OPERATION, "UpdateItem"), null, mutation, null, List.of());
        Node consumer = new Node("c-root", "p-sqs", NodeKind.LAMBDA, "order-billing", NodeStatus.OK, t0.plusMillis(5000),
                Duration.ofMillis(4), Duration.ofMillis(35), Map.of(tech.neural7.trace2local.otel.OtelAttributeNames.FAAS_INVOCATION_ID, "req-2"), new Payload("{\"Records\":[]}", null),
                null, null, List.of(ddb));
        Node sqs = new Node("p-sqs", "p-root", NodeKind.SQS, "SQS: orders-queue", NodeStatus.OK, t0.plusMillis(20),
                Duration.ofMillis(9), Duration.ofMillis(9), Map.of(), null, null, null, List.of(consumer));
        Node root = new Node("p-root", null, NodeKind.LAMBDA, "order-processor", NodeStatus.ERROR, t0, Duration.ofMillis(1),
                Duration.ofMillis(26), Map.of(), null, null, new ErrorInfo("java.lang.IllegalStateException", "limite", null), List.of(sqs));
        Execution e = new Execution("exec-1", "4bf92f3577b34da6a3ce929d0e0e4736", ExecutionStatus.FAILED, Trigger.LAMBDA_EVENT,
                t0, Duration.ofMillis(5045), List.of(root), new ExecutionMetrics(4, 4, 0, 0),
                List.of(new Warning(Warning.WarningKind.CONTEXT_LOST, 1, "aviso", t0)));

        Execution back = ExecutionJson.fromJson(JsonCodec.MAPPER.valueToTree(e));
        assertThat(back).isEqualTo(e);

        // envelope .tvtrace também é aceito
        var tv = JsonCodec.MAPPER.createObjectNode();
        tv.putObject("manifest").put("format", "tvtrace");
        tv.set("execution", JsonCodec.MAPPER.valueToTree(e));
        assertThat(ExecutionJson.fromJson(tv)).isEqualTo(e);
    }

    @Test
    void rejectsSomethingThatIsNotAnExecution() {
        assertThatThrownBy(() -> ExecutionJson.fromJson(JsonCodec.MAPPER.createObjectNode().put("x", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
