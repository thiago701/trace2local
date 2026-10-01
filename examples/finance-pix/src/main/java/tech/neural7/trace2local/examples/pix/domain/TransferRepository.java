package tech.neural7.trace2local.examples.pix.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import tech.neural7.trace2local.examples.pix.infra.Aws;
import tech.neural7.trace2local.examples.pix.infra.Env;
import tech.neural7.trace2local.examples.pix.infra.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Estado das transferências no DynamoDB (consultado pelo GET e pelos consumidores). */
public final class TransferRepository {

    private final String table = Env.get("PIX_TRANSFERS_TABLE", "pix-transfers");

    public void create(String transferId, String status, TransferRequest req, String endToEndId, String receiverIspb,
                       String receiverName, String decision) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("transferId", s(transferId));
        item.put("status", s(status));
        item.put("payerAccountId", s(req.payerAccountId()));
        item.put("amount", AttributeValue.fromN(req.amount().toPlainString()));
        item.put("endToEndId", s(endToEndId));
        item.put("receiverIspb", s(receiverIspb));
        item.put("receiverName", s(receiverName));
        item.put("fraudDecision", s(decision));
        item.put("createdAt", s(Instant.now().toString()));
        Aws.dynamo().putItem(r -> r.tableName(table).item(item)
                .conditionExpression("attribute_not_exists(transferId)"));
    }

    /** Transição de estado protegida (só sai de {@code from}); falso se já transitou (reentrega). */
    public boolean transition(String transferId, String from, String to, Map<String, String> extra) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        StringBuilder set = new StringBuilder("SET #s = :to");
        names.put("#s", "status");
        values.put(":to", s(to));
        values.put(":from", s(from));
        int i = 0;
        for (Map.Entry<String, String> e : extra.entrySet()) {
            set.append(", #a").append(i).append(" = :v").append(i);
            names.put("#a" + i, e.getKey());
            values.put(":v" + i, s(e.getValue()));
            i++;
        }
        try {
            Aws.dynamo().updateItem(r -> r.tableName(table).key(Map.of("transferId", s(transferId)))
                    .updateExpression(set.toString()).conditionExpression("#s = :from")
                    .expressionAttributeNames(names).expressionAttributeValues(values));
            return true;
        } catch (ConditionalCheckFailedException alreadyMoved) {
            return false;
        }
    }

    public Optional<ObjectNode> find(String transferId) {
        Map<String, AttributeValue> item = Aws.dynamo().getItem(r -> r.tableName(table)
                .key(Map.of("transferId", s(transferId)))).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode out = Json.object();
        out.put("transferId", item.get("transferId").s());
        out.put("status", item.get("status").s());
        out.put("amount", new java.math.BigDecimal(item.get("amount").n()));
        out.put("endToEndId", text(item, "endToEndId"));
        out.putObject("receiver").put("ispb", text(item, "receiverIspb")).put("name", text(item, "receiverName"));
        out.put("createdAt", text(item, "createdAt"));
        if (item.containsKey("settledAt")) {
            out.put("settledAt", text(item, "settledAt"));
        }
        return Optional.of(out);
    }

    private static String text(Map<String, AttributeValue> item, String k) {
        AttributeValue v = item.get(k);
        return v == null ? null : v.s();
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v == null ? "" : v);
    }
}
