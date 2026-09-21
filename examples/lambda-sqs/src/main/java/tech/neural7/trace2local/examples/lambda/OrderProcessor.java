package tech.neural7.trace2local.examples.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import tech.neural7.trace2local.aws.Trace2LocalAws;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.lambda.Trace2LocalLambda;
import tech.neural7.trace2local.lambda.Trace2LocalLambdaHandler;

import java.net.URI;
import java.util.Map;

/**
 * Função Lambda do projeto de teste/validação (runtime java21 no LocalStack):
 * <ol>
 *   <li>grava o pedido no DynamoDB via {@link Trace2LocalAws#instrument} — o
 *       {@code DynamoDbDeltaInterceptor} captura o delta EXACT (ADR-003);</li>
 *   <li>publica um evento na fila SQS (span de produtor; sem consumidor nesta
 *       demo, o ramo fica declarado como "sem consumidor observado");</li>
 *   <li>o {@link Trace2LocalLambdaHandler} abre o span raiz (SERVER com
 *       {@code faas.name} → nó LAMBDA), correlaciona as mutações e faz o flush
 *       síncrono OTLP + {@code /t2lingest/v1/mutations} para o Station (ADR-002).</li>
 * </ol>
 *
 * <p>Endpoint do LocalStack resolvido em cascata: propriedade de sistema
 * {@code localstack.endpoint} (testes) → env {@code LOCALSTACK_ENDPOINT} → env
 * {@code AWS_ENDPOINT_URL} (injetada pelo LocalStack dentro do emulador Lambda)
 * → {@code http://localhost:4566}.
 */
public final class OrderProcessor extends Trace2LocalLambdaHandler<Map<String, String>, String> {

    private final DynamoDbClient dynamoDb;
    private final SqsClient sqs;
    private final String tableName;
    private final String queueUrl;

    /** Modo deploy: configuração via ambiente ({@code TRACE2LOCAL_STATION_ENDPOINT}). */
    public OrderProcessor() {
        this(Trace2LocalLambda.configFromEnv());
    }

    /** Modo teste/embarcado: config explícita (Station do IT em processo). */
    public OrderProcessor(Trace2LocalConfig cfg) {
        super(cfg);
        String endpoint = localstackEndpoint();
        this.dynamoDb = Trace2LocalAws.instrument(
                        DynamoDbClient.builder()
                                .region(Region.US_EAST_1)
                                .endpointOverride(URI.create(endpoint)),
                        cfg)
                .build();
        // SQS SEM interceptor do OTel: o span de produtor é MANUAL (ver sendToQueue) —
        // a instrumentação automática do AWS SDK não injeta AWSTraceHeader no
        // SendMessage direto (descoberto no loop de validação; o manual garante a
        // correlação §4.11 e o header no atributo de sistema da mensagem)
        this.sqs = SqsClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .build();
        this.tableName = env("ORDERS_TABLE", "orders");
        this.queueUrl = env("ORDERS_QUEUE_URL", endpoint + "/000000000000/orders-queue");
    }

    @Override
    protected String handle(Map<String, String> input, Context ctx) {
        String orderId = value(input, "orderId", "ORDER-" + (ctx != null ? ctx.getAwsRequestId() : "unknown"));
        String customerId = value(input, "customerId", "anonymous");
        String total = value(input, "total", "0.00");

        // 1) DynamoDB — delta EXACT capturado pelo DynamoDbDeltaInterceptor
        dynamoDb.putItem(r -> r.tableName(tableName).item(Map.of(
                "pk", AttributeValue.fromS(orderId),
                "customerId", AttributeValue.fromS(customerId),
                "total", AttributeValue.fromN(total))));

        // 2) validação de negócio (caminho de erro — evidência de execução vermelha):
        //    o PutItem JÁ aconteceu: a árvore mostra o ramo DynamoDB OK sob uma raiz FAILED
        if ("true".equals(input != null ? input.get("fail") : null)) {
            throw new IllegalStateException("ordem recusada: " + orderId + " excede o limite de crédito");
        }

        // 3) SQS — span de produtor MANUAL com AWSTraceHeader (atributo de sistema):
        //    permite ao consumidor OrderBillingProcessor continuar a MESMA árvore (§4.11)
        sendToQueue(orderId, customerId);

        return "{\"status\":\"ok\",\"orderId\":\"" + orderId + "\"}";
    }

    private void sendToQueue(String orderId, String customerId) {
        io.opentelemetry.api.trace.Tracer tracer =
                tech.neural7.trace2local.otel.Trace2LocalOtel.get()
                        .getTracer("tech.neural7.trace2local.examples:lambda-sqs");
        io.opentelemetry.api.trace.Span producer = tracer.spanBuilder("orders-queue send")
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.PRODUCER)
                .setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.MESSAGING_SYSTEM, "aws_sqs")
                .setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.MESSAGING_DESTINATION, "orders-queue")
                .setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.MESSAGING_OPERATION, "publish")
                .setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.AWS_SQS_QUEUE, queueUrl)
                .startSpan();
        try (var scope = producer.makeCurrent()) {
            String traceHeader = "Root=1-" + producer.getSpanContext().getTraceId()
                    + ";Parent=" + producer.getSpanContext().getSpanId() + ";Sampled=1";
            sqs.sendMessage(r -> r.queueUrl(queueUrl)
                    .messageBody("{\"orderId\":\"" + orderId + "\",\"customerId\":\"" + customerId + "\"}")
                    .messageSystemAttributes(Map.of(
                            software.amazon.awssdk.services.sqs.model.MessageSystemAttributeNameForSends.AWS_TRACE_HEADER,
                            software.amazon.awssdk.services.sqs.model.MessageSystemAttributeValue.builder()
                                    .dataType("String").stringValue(traceHeader).build())));
        } finally {
            producer.end();
        }
    }

    private static String value(Map<String, String> input, String key, String fallback) {
        String v = input != null ? input.get(key) : null;
        return v == null || v.isBlank() ? fallback : v;
    }

    static String localstackEndpoint() {
        String v = System.getProperty("localstack.endpoint");
        if (isBlank(v)) {
            v = System.getenv("LOCALSTACK_ENDPOINT");
        }
        if (isBlank(v)) {
            // o LocalStack injeta AWS_ENDPOINT_URL no ambiente da função em execução
            v = System.getenv("AWS_ENDPOINT_URL");
        }
        return isBlank(v) ? "http://localhost:4566" : v;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return isBlank(v) ? fallback : v;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
