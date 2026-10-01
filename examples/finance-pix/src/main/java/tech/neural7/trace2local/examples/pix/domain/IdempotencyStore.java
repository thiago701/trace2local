package tech.neural7.trace2local.examples.pix.domain;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import tech.neural7.trace2local.examples.pix.infra.Aws;
import tech.neural7.trace2local.examples.pix.infra.Env;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Idempotência por {@code Idempotency-Key} (DynamoDB, escrita condicional + TTL de 24 h):
 * reenvio com o mesmo payload devolve a MESMA resposta; payload diferente = 409.
 */
public final class IdempotencyStore {

    public sealed interface Outcome {
        record New() implements Outcome {}
        record Replay(int status, String body) implements Outcome {}
        record InProgress() implements Outcome {}
        record Conflict() implements Outcome {}
    }

    private final String table = Env.get("PIX_IDEMPOTENCY_TABLE", "pix-idempotency");

    public Outcome begin(String key, String canonicalPayload) {
        String hash = sha256(canonicalPayload);
        try {
            Aws.dynamo().putItem(r -> r.tableName(table).item(Map.of(
                            "idempotencyKey", AttributeValue.fromS(key),
                            "payloadHash", AttributeValue.fromS(hash),
                            "state", AttributeValue.fromS("IN_PROGRESS"),
                            "expiresAt", AttributeValue.fromN(String.valueOf(Instant.now().plusSeconds(86_400).getEpochSecond()))))
                    .conditionExpression("attribute_not_exists(idempotencyKey)"));
            return new Outcome.New();
        } catch (ConditionalCheckFailedException exists) {
            Map<String, AttributeValue> item = Aws.dynamo().getItem(r -> r.tableName(table)
                    .key(Map.of("idempotencyKey", AttributeValue.fromS(key))).consistentRead(true)).item();
            if (item == null || item.isEmpty()) {
                return new Outcome.InProgress();
            }
            if (!hash.equals(item.get("payloadHash").s())) {
                return new Outcome.Conflict();
            }
            if (!"COMPLETED".equals(item.get("state").s())) {
                return new Outcome.InProgress();
            }
            return new Outcome.Replay(Integer.parseInt(item.get("responseStatus").n()), item.get("responseBody").s());
        }
    }

    public void complete(String key, int status, String body) {
        Aws.dynamo().updateItem(r -> r.tableName(table)
                .key(Map.of("idempotencyKey", AttributeValue.fromS(key)))
                .updateExpression("SET #st = :done, responseStatus = :rs, responseBody = :rb")
                .expressionAttributeNames(Map.of("#st", "state"))
                .expressionAttributeValues(Map.of(":done", AttributeValue.fromS("COMPLETED"),
                        ":rs", AttributeValue.fromN(String.valueOf(status)), ":rb", AttributeValue.fromS(body))));
    }

    /** Falha técnica: libera a chave para o cliente tentar de novo. */
    public void abandon(String key) {
        Aws.dynamo().deleteItem(r -> r.tableName(table).key(Map.of("idempotencyKey", AttributeValue.fromS(key))));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
