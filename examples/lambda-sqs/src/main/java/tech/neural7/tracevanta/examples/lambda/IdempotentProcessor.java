package tech.neural7.tracevanta.examples.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import tech.neural7.tracevanta.aws.TraceVantaAws;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.lambda.TraceVantaLambda;
import tech.neural7.tracevanta.lambda.TraceVantaLambdaHandler;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Cenário de MONITORAMENTO DE IDEMPOTÊNCIA (caso de uso de qualquer domínio:
 * pagamentos, pedidos, webhooks…): uma escrita condicional
 * ({@code attribute_not_exists(pk)}) funciona como guarda de idempotência.
 *
 * <ul>
 *   <li><b>1ª chamada</b> (chave nova) → cria: árvore mostra
 *       {@code IdempotencyGuard → DynamoDB (CREATE/EXACT)};</li>
 *   <li><b>2ª chamada</b> (mesma chave) → a condição falha, o handler devolve
 *       {@code duplicate_ignored} SEM efeito colateral — e o canvas mostra a
 *       história: guarda OK + nó DynamoDB VERMELHO com
 *       {@code ConditionalCheckFailedException} e SEM delta (nada foi escrito
 *       na tentativa duplicada).</li>
 * </ul>
 *
 * É exatamente o que um monitor de idempotência precisa provar: a árvore
 * distingue "criou" de "recusou duplicado", e o estado do banco confirma.
 */
public final class IdempotentProcessor extends TraceVantaLambdaHandler<Map<String, String>, String> {

    public static final String TABLE = "idempotency";

    private final DynamoDbClient dynamoDb;

    public IdempotentProcessor() {
        this(TraceVantaLambda.configFromEnv());
    }

    public IdempotentProcessor(TraceVantaConfig cfg) {
        super(cfg);
        this.dynamoDb = TraceVantaAws.instrument(
                        DynamoDbClient.builder()
                                .region(Region.US_EAST_1)
                                .endpointOverride(URI.create(OrderProcessor.localstackEndpoint())),
                        cfg)
                .build();
    }

    @Override
    protected String handle(Map<String, String> input, Context ctx) {
        String key = input != null && input.get("idempotencyKey") != null
                ? input.get("idempotencyKey")
                : "IDEM-" + (ctx != null ? ctx.getAwsRequestId() : "unknown");
        String payload = input != null && input.get("payload") != null ? input.get("payload") : "{}";

        // nó BUSINESS explícito: a guarda é o passo de negócio monitorável
        var tracer = tech.neural7.tracevanta.otel.TraceVantaOtel.get()
                .getTracer("tech.neural7.tracevanta.examples:idempotency");
        var guard = tracer.spanBuilder("IdempotencyGuard")
                .setAttribute(tech.neural7.tracevanta.otel.TraceVantaAttributes.BUSINESS, "true")
                .startSpan();
        try (var scope = guard.makeCurrent()) {
            try {
                dynamoDb.putItem(r -> r.tableName(TABLE)
                        .item(Map.of(
                                "pk", AttributeValue.fromS(key),
                                "payload", AttributeValue.fromS(payload),
                                "createdAt", AttributeValue.fromS(Instant.now().toString())))
                        .conditionExpression("attribute_not_exists(pk)"));
                return "{\"status\":\"created\",\"key\":\"" + key + "\"}";
            } catch (ConditionalCheckFailedException duplicate) {
                // idempotência PRESERVADA: a condição recusou a segunda escrita;
                // o nó DynamoDB fica VERMELHO com o erro e SEM delta — o monitor
                // enxerga exatamente o que aconteceu (e o que NÃO aconteceu)
                return "{\"status\":\"duplicate_ignored\",\"key\":\"" + key + "\"}";
            }
        } finally {
            guard.end();
        }
    }
}
