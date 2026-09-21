package tech.neural7.trace2local.examples.payments;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import org.springframework.stereotype.Repository;
import tech.neural7.trace2local.aws.Trace2LocalAws;
import tech.neural7.trace2local.config.Trace2LocalConfig;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Acesso a dados: escrita condicional (guarda de idempotência) + update com
 * READ-BACK — o delta do UpdateItem ganha before (ALL_OLD) e after exato
 * (releitura dentro do span), fechando o desvio §4.10.
 */
@Repository
public class PaymentRepository {

    public static final String TABLE = "payments";

    private final DynamoDbClient dynamoDb;

    public PaymentRepository(Trace2LocalConfig cfg) {
        String endpoint = System.getenv().getOrDefault("LOCALSTACK_ENDPOINT", "http://localhost:4566");
        this.dynamoDb = Trace2LocalAws.instrumentWithReadBack(
                        DynamoDbClient.builder(), cfg)
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .build();
    }

    /** Resultado do save: o registro + se a guarda de idempotência recusou a escrita. */
    public record SaveResult(Payment payment, boolean duplicated) {}

    /** Guarda de idempotência: grava apenas se a chave não existir. */
    public SaveResult save(Payment payment) {
        try {
            dynamoDb.putItem(r -> r.tableName(TABLE)
                    .item(Map.of(
                            "pk", AttributeValue.fromS(payment.key()),
                            "payer", AttributeValue.fromS(payment.payer()),
                            "amount", AttributeValue.fromN(payment.amount().toPlainString()),
                            "status", AttributeValue.fromS(payment.status()),
                            "createdAt", AttributeValue.fromS(Instant.now().toString())))
                    .conditionExpression("attribute_not_exists(pk)"));
            return new SaveResult(payment, false);
        } catch (ConditionalCheckFailedException duplicate) {
            // idempotência: devolve o registro existente — nada foi reescrito
            return new SaveResult(find(payment.key()), true);
        }
    }

    /** Confirmação: update com read-back (before+after EXACT no inspector). */
    public Payment confirm(String key) {
        dynamoDb.updateItem(r -> r.tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS(key)))
                .updateExpression("SET #st = :s")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":s", AttributeValue.fromS("CONFIRMED"))));
        return find(key);
    }

    public Payment find(String key) {
        var item = dynamoDb.getItem(r -> r.tableName(TABLE)
                        .key(Map.of("pk", AttributeValue.fromS(key))))
                .item();
        if (item == null || item.isEmpty()) {
            return null;
        }
        return new Payment(
                item.get("pk").s(),
                item.get("payer").s(),
                new BigDecimal(item.get("amount").n()),
                item.containsKey("status") ? item.get("status").s() : "PENDING");
    }
}
