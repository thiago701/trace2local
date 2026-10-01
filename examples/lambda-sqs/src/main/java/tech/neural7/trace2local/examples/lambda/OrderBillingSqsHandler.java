package tech.neural7.trace2local.examples.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.api.trace.SpanContext;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tech.neural7.trace2local.aws.Trace2LocalAws;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.lambda.Trace2LocalLambda;
import tech.neural7.trace2local.lambda.Trace2LocalLambdaHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumidor REAL da fila: função {@code order-billing} acionada pelo
 * <i>event source mapping</i> SQS → Lambda do LocalStack (o mesmo contrato da AWS:
 * {@code {"Records":[{"body":…,"attributes":{"AWSTraceHeader":…}}]}}).
 *
 * <p>Diferente do {@link OrderBillingProcessor} (que recebe o header pronto, para
 * testes), aqui o parent remoto sai do atributo de sistema {@code AWSTraceHeader}
 * do primeiro registro — a continuação aparece na MESMA árvore do produtor e a
 * espera na fila vira um segmento medido (produtor terminou → consumidor começou).
 */
public final class OrderBillingSqsHandler extends Trace2LocalLambdaHandler<Map<String, Object>, String> {

    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*\"([^\"]{1,64})\"");

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public OrderBillingSqsHandler() {
        this(Trace2LocalLambda.configFromEnv());
    }

    public OrderBillingSqsHandler(Trace2LocalConfig cfg) {
        super(cfg);
        this.dynamoDb = Trace2LocalAws.instrumentWithReadBack(DynamoDbClient.builder(), cfg)
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(OrderProcessor.localstackEndpoint()))
                .build();
        String t = System.getenv("ORDERS_TABLE");
        this.tableName = t == null || t.isBlank() ? "orders" : t;
    }

    @Override
    protected SpanContext remoteParentOf(Map<String, Object> event, Context context) {
        return OrderBillingProcessor.parseTraceHeader(traceHeaderOf(event));
    }

    /** {@code Records[0].attributes.AWSTraceHeader} do evento SQS (ou {@code null}). */
    static String traceHeaderOf(Map<String, Object> event) {
        Map<String, Object> first = firstRecord(event);
        Object attrs = first != null ? first.get("attributes") : null;
        Object header = attrs instanceof Map<?, ?> m ? m.get("AWSTraceHeader") : null;
        return header != null ? header.toString() : null;
    }

    @Override
    protected String handle(Map<String, Object> event, Context ctx) {
        Object records = event != null ? event.get("Records") : null;
        int billed = 0;
        if (records instanceof List<?> list) {
            for (Object r : list) {
                if (!(r instanceof Map<?, ?> rec)) {
                    continue;
                }
                String orderId = orderIdOf(String.valueOf(rec.get("body")));
                System.out.println("INFO cobrança iniciada para " + orderId + " (mensagem " + rec.get("messageId") + ")");
                dynamoDb.updateItem(u -> u.tableName(tableName)
                        .key(Map.of("pk", AttributeValue.fromS(orderId)))
                        .updateExpression("SET #st = :s")
                        .expressionAttributeNames(Map.of("#st", "status"))
                        .expressionAttributeValues(Map.of(":s", AttributeValue.fromS("BILLED"))));
                System.out.println("INFO pedido " + orderId + " marcado como BILLED");
                billed++;
            }
        }
        return "{\"billed\":" + billed + "}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRecord(Map<String, Object> event) {
        Object records = event != null ? event.get("Records") : null;
        if (records instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    static String orderIdOf(String body) {
        Matcher m = ORDER_ID.matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : "ORDER-unknown";
    }
}
