package tech.neural7.trace2local.examples.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.testing.Trace2LocalAssertions;
import tech.neural7.trace2local.testing.Trace2LocalTest;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integração JC-1 em Spring: o @Trace2Local do serviço vira nó BUSINESS na
 * árvore — e o gêmeo negativo (§10) prova que nada de DynamoDB apareceu com os
 * clientes mockados. Sem Docker: o ciclo completo contra LocalStack fica no
 * perfil {@code it} do Maven (Testcontainers — SPEC §10).
 */
@SpringBootTest(properties = {
        "trace2local.port=0",
        "localstack.endpoint=http://localhost:4566",
        "orders.sns-topic-arn=arn:aws:sns:us-east-1:000000000000:order-events"})
@ActiveProfiles("dev")
@Trace2LocalTest
class OrderFlowIntegrationTest {

    @Autowired
    OrderService service;

    @MockitoBean
    DynamoDbClient dynamoDbClient;

    @MockitoBean
    SnsClient snsClient;

    @Test
    void createOrderAppearsAsBusinessNode() {
        when(dynamoDbClient.putItem(any(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder().build());
        when(snsClient.publish(any(software.amazon.awssdk.services.sns.model.PublishRequest.class)))
                .thenReturn(software.amazon.awssdk.services.sns.model.PublishResponse.builder().build());

        Order order = service.create(new CreateOrderRequest("C-1", new BigDecimal("250.00")));

        assertThat(order.orderId()).startsWith("ORDER#");
        Trace2LocalAssertions.awaitLatestExecution(Duration.ofSeconds(10))
                .hasNode(NodeKind.BUSINESS, "CreateOrder")
                // gêmeo negativo: clientes mockados não emitem spans AWS
                .doesNotHaveNode(NodeKind.DYNAMODB, "orders");
    }
}
