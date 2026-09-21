package tech.neural7.tracevanta.server;

import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.model.DataMutation;
import tech.neural7.tracevanta.model.Execution;
import tech.neural7.tracevanta.model.ExecutionMetrics;
import tech.neural7.tracevanta.model.ExecutionStatus;
import tech.neural7.tracevanta.model.MutationFidelity;
import tech.neural7.tracevanta.model.MutationKind;
import tech.neural7.tracevanta.model.Node;
import tech.neural7.tracevanta.model.NodeKind;
import tech.neural7.tracevanta.model.NodeStatus;
import tech.neural7.tracevanta.model.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Descoberta de negócio por contexto + engenharia reversa + glossário:
 * a narrativa precisa sair em linguagem de negócio, em ordem de fluxo,
 * com o glossário sobrescrevendo a nota quando o time documenta o termo.
 */
class StoryServiceTest {

    private final StoryService service = new StoryService();

    @Test
    void buildsNarrativeWithIntroOrderedStepsAndConclusion() {
        Execution execution = sample();

        StoryService.Story story = service.storyFor(execution);

        assertThat(story.executionId()).isEqualTo("TV-00077");
        assertThat(story.intro()).contains("começou");
        assertThat(story.steps()).hasSize(4);

        // ordem de fluxo (DFS): HTTP → BUSINESS → DynamoDB → SNS
        assertThat(story.steps().get(0).kind()).isEqualTo("HTTP_SERVER");
        assertThat(story.steps().get(0).text()).contains("POST /orders").contains("status 200");
        assertThat(story.steps().get(1).kind()).isEqualTo("BUSINESS");
        assertThat(story.steps().get(1).text()).contains("cria o recurso"); // CreateOrder → verbo de negócio
        assertThat(story.steps().get(2).kind()).isEqualTo("DYNAMODB");
        assertThat(story.steps().get(2).text()).contains("descrição do glossário aplicada"); // docs vencem a inferência
        assertThat(story.steps().get(3).kind()).isEqualTo("SNS");
        assertThat(story.steps().get(3).text()).contains("publica o evento"); // messaging.publish → verbo

        // desfecho: sucesso + duração + contagem de mutações
        assertThat(story.conclusion()).contains("com sucesso").contains("42 ms").contains("alteraram dados");
        assertThat(story.status()).isEqualTo("COMPLETED");
    }

    @Test
    void humanizesCamelCaseBusinessNames() {
        assertThat(StoryService.humanize("CreateOrder")).isEqualTo("Create Order");
        assertThat(StoryService.humanize("confirmOrderBilling")).isEqualTo("Confirm Order Billing");
        assertThat(StoryService.humanize("")).isEqualTo("funcionalidade");
    }

    @Test
    void errorStepIsMarkedAndConclusionReflectsFailure() {
        Node failing = new Node("s1", null, NodeKind.BUSINESS, "ConfirmOrder", NodeStatus.ERROR,
                Instant.now(), Duration.ofMillis(5), Duration.ofMillis(10), Map.of(), null, null,
                new tech.neural7.tracevanta.model.ErrorInfo("E", "condição falhou", null), List.of());
        Execution execution = new Execution("TV-00078", "trace", ExecutionStatus.FAILED, Trigger.TEST,
                Instant.now(), Duration.ofMillis(10), List.of(failing),
                new ExecutionMetrics(1, 1, 0, 0), List.of());

        StoryService.Story story = service.storyFor(execution);

        assertThat(story.steps().get(0).error()).isTrue();
        assertThat(story.conclusion()).contains("com erro");
    }

    private static Execution sample() {
        Node root = new Node("root", null, NodeKind.HTTP_SERVER, "POST /orders", NodeStatus.OK,
                Instant.now(), Duration.ofMillis(2), Duration.ofMillis(42),
                Map.of(tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_METHOD, "POST",
                        tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_ROUTE, "/orders",
                        tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_STATUS, "200"),
                null, null, null, List.of());
        Node business = new Node("b1", "root", NodeKind.BUSINESS, "CreateOrder", NodeStatus.OK,
                Instant.now(), Duration.ofMillis(10), Duration.ofMillis(38), Map.of(), null, null, null, List.of());
        Node dynamo = new Node("d1", "b1", NodeKind.DYNAMODB, "DynamoDB: orders", NodeStatus.OK,
                Instant.now(), Duration.ofMillis(5), Duration.ofMillis(20),
                Map.of(tech.neural7.tracevanta.otel.OtelAttributeNames.AWS_DYNAMO_TABLES, "orders",
                        tech.neural7.tracevanta.otel.OtelAttributeNames.RPC_METHOD, "PutItem"),
                null,
                new DataMutation(MutationKind.CREATE, "orders", "ORDER#77", null, null, List.of(), MutationFidelity.EXACT),
                null, List.of());
        Node sns = new Node("s1", "b1", NodeKind.SNS, "SNS: order-events", NodeStatus.OK,
                Instant.now(), Duration.ofMillis(3), Duration.ofMillis(12),
                Map.of(tech.neural7.tracevanta.otel.OtelAttributeNames.AWS_SNS_TOPIC,
                        "arn:aws:sns:us-east-1:000000000000:order-events",
                        tech.neural7.tracevanta.otel.OtelAttributeNames.RPC_METHOD, "Publish"),
                null, null, null, List.of());
        business = new Node("b1", "root", NodeKind.BUSINESS, "CreateOrder", NodeStatus.OK,
                Instant.now(), Duration.ofMillis(10), Duration.ofMillis(38), Map.of(), null, null, null,
                List.of(dynamo, sns));
        root = new Node("root", null, NodeKind.HTTP_SERVER, "POST /orders", NodeStatus.OK,
                Instant.now(), Duration.ofMillis(2), Duration.ofMillis(42),
                Map.of(tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_METHOD, "POST",
                        tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_ROUTE, "/orders",
                        tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_STATUS, "200"),
                null, null, null, List.of(business));
        return new Execution("TV-00077", "trace-77", ExecutionStatus.COMPLETED, Trigger.EXTERNAL,
                Instant.now(), Duration.ofMillis(42), List.of(root),
                new ExecutionMetrics(4, 3, 0, 0), List.of());
    }
}
