package tech.neural7.trace2local.examples.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tech.neural7.trace2local.aws.Trace2LocalAws;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.lambda.Trace2LocalLambda;
import tech.neural7.trace2local.lambda.Trace2LocalLambdaHandler;

import java.net.URI;
import java.util.Map;

/**
 * Consumidor da fila SQS do cenário (papel do billing): marca o pedido BILLED
 * no DynamoDB. A mensagem carrega o {@code AWSTraceHeader} (atributo gerado
 * pelo OTel no SendMessage do {@link OrderProcessor}) — {@link #remoteParentOf}
 * o devolve como parent remoto e o span raiz desta invocação vira FILHO do span
 * de produtor: a continuação aparece NA MESMA ÁRVORE do Station (SPEC §4.11,
 * o equivalente Lambda da JC-3 do order-service).
 */
public final class OrderBillingProcessor extends Trace2LocalLambdaHandler<Map<String, String>, String> {

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public OrderBillingProcessor() {
        this(Trace2LocalLambda.configFromEnv());
    }

    public OrderBillingProcessor(Trace2LocalConfig cfg) {
        super(cfg);
        String endpoint = OrderProcessor.localstackEndpoint();
        // instrumentWithReadBack POR PRIMEIRO na cadeia: o wrapper grava
        // endpointOverride/region definidos depois — o UpdateItem do billing
        // ganha before (ALL_OLD) + after (releitura) EXACT (§4.10)
        this.dynamoDb = Trace2LocalAws.instrumentWithReadBack(
                        DynamoDbClient.builder(), cfg)
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .build();
        this.tableName = System.getenv().getOrDefault("ORDERS_TABLE", "orders");
    }

    @Override
    protected SpanContext remoteParentOf(Map<String, String> input, Context context) {
        return parseTraceHeader(input != null ? input.get("traceHeader") : null);
    }

    @Override
    protected String handle(Map<String, String> input, Context ctx) {
        String orderId = input != null && input.get("orderId") != null
                ? input.get("orderId")
                : "ORDER-unknown";
        // delta UPDATE capturado pelo DynamoDbDeltaInterceptor (before ausente — I3 declarado)
        dynamoDb.updateItem(r -> r.tableName(tableName)
                .key(Map.of("pk", AttributeValue.fromS(orderId)))
                .updateExpression("SET #st = :s")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":s", AttributeValue.fromS("BILLED"))));
        return "{\"status\":\"billed\",\"orderId\":\"" + orderId + "\"}";
    }

    /** {@code Root=1-<traceHex>;Parent=<spanHex>;Sampled=1} → {@link SpanContext} remoto. */
    static SpanContext parseTraceHeader(String header) {
        if (header == null || header.isBlank()) {
            return SpanContext.getInvalid();
        }
        String traceId = null;
        String parentId = null;
        boolean sampled = false;
        for (String part : header.split(";")) {
            if (part.startsWith("Root=")) {
                String v = part.substring("Root=".length()).trim();
                int dash = v.indexOf('-');
                traceId = dash >= 0 ? v.substring(dash + 1) : v;
            } else if (part.startsWith("Parent=")) {
                parentId = part.substring("Parent=".length()).trim();
            } else if (part.startsWith("Sampled=")) {
                sampled = "1".equals(part.substring("Sampled=".length()).trim());
            }
        }
        if (traceId == null || parentId == null) {
            return SpanContext.getInvalid();
        }
        try {
            return SpanContext.createFromRemoteParent(traceId, parentId,
                    sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                    TraceState.getDefault());
        } catch (RuntimeException malformed) {
            return SpanContext.getInvalid(); // header ilegível: nova árvore, sem quebrar a função
        }
    }
}
