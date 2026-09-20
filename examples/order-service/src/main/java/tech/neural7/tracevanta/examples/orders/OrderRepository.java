package tech.neural7.tracevanta.examples.orders;

import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Repository
public class OrderRepository {

    public static final String TABLE = "orders";

    private final DynamoDbClient dynamoDb;

    public OrderRepository(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    public void save(Order order) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(TABLE)
                .item(toItem(order))
                .build());
    }

    public Optional<Order> findById(String orderId) {
        var response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS(orderId)))
                .build());
        return response.hasItem() ? Optional.of(fromItem(response.item())) : Optional.empty();
    }

    /** Confirma o pedido com condição — JC-2: condição violada vira 409 com o delta na árvore. */
    public Order confirm(String orderId) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(TABLE)
                    .key(Map.of("pk", AttributeValue.fromS(orderId)))
                    .updateExpression("SET #status = :confirmed")
                    .conditionExpression("#status = :pending")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":confirmed", AttributeValue.fromS("CONFIRMED"),
                            ":pending", AttributeValue.fromS("PENDING")))
                    .build());
            // a resposta foi restaurada pelo interceptor (R-01) — relê o item para o after
            return findById(orderId).orElse(null);
        } catch (ConditionalCheckFailedException e) {
            throw new OrderConflictException(orderId, e);
        }
    }

    /** Billing: marca o pedido como BILLED (após o confirm — JC-3). */
    public void markBilled(String orderId) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS(orderId)))
                .updateExpression("SET #status = :billed")
                .conditionExpression("#status = :confirmed")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":billed", AttributeValue.fromS("BILLED"),
                        ":confirmed", AttributeValue.fromS("CONFIRMED")))
                .build());
    }

    private static Map<String, AttributeValue> toItem(Order order) {
        return Map.of(
                "pk", AttributeValue.fromS(order.orderId()),
                "customerId", AttributeValue.fromS(order.customerId()),
                "total", AttributeValue.fromN(order.total().toPlainString()),
                "status", AttributeValue.fromS(order.status()));
    }

    private static Order fromItem(Map<String, AttributeValue> item) {
        return new Order(
                item.get("pk").s(),
                item.get("customerId").s(),
                new BigDecimal(item.get("total").n()),
                item.get("status").s());
    }
}
