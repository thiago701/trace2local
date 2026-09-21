package tech.neural7.trace2local.examples.lambda;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tech.neural7.trace2local.config.Trace2LocalConfig;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Runner de demonstração/evidência: executa as TRÊS jornadas do cenário
 * (feliz, erro, consumidor) contra o LocalStack do compose e o Station em
 * :19877 — sem precisar do emulador Lambda para cada passo.
 *
 * <pre>
 * mvn -q -f examples/lambda-sqs/pom.xml -DskipTests package
 * java -cp target/lambda-sqs-bundle.jar \
 *   -Dlocalstack.endpoint=http://localhost:4567 \
 *   -Dtrace2local.station.endpoint=http://127.0.0.1:19877 \
 *   tech.neural7.trace2local.examples.lambda.LambdaSqsDemoRun
 * </pre>
 */
public final class LambdaSqsDemoRun {

    private LambdaSqsDemoRun() {}

    public static void main(String[] args) throws Exception {
        String endpoint = System.getProperty("localstack.endpoint",
                env("LOCALSTACK_ENDPOINT", "http://localhost:4566"));
        Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                .stationEndpoint(System.getProperty("trace2local.station.endpoint",
                        env("TRACE2LOCAL_STATION_ENDPOINT", "http://127.0.0.1:19877")))
                .stationToken(System.getProperty("trace2local.station.token",
                        env("TRACE2LOCAL_STATION_TOKEN", null)))
                .flushTimeoutMs(1500)
                .build();

        String orderId = "ORDER-D" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        // J1 — jornada feliz
        OrderProcessor processor = new OrderProcessor(cfg);
        String ok = processor.handleRequest(
                Map.of("orderId", orderId, "customerId", "C-DEMO", "total", "199.90"),
                new DemoContext("req-demo-1", "order-processor"));
        System.out.println("[DEMO] J1 ok: " + ok);

        // J2 — jornada de erro (o PutItem persiste; a execução fecha VERMELHA)
        try {
            processor.handleRequest(Map.of("orderId", "ORDER-FAIL-" + orderId,
                    "customerId", "C-DEMO", "total", "9999.00", "fail", "true"),
                    new DemoContext("req-demo-2", "order-processor"));
        } catch (IllegalStateException expected) {
            System.out.println("[DEMO] J2 falhou como esperado: " + expected.getMessage());
        }

        // J3 — consumidor continua a MESMA árvore via AWSTraceHeader
        try (SqsClient sqs = SqsClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .build()) {
            String queueUrl = env("ORDERS_QUEUE_URL", endpoint + "/000000000000/orders-queue");
            // a fila acumula mensagens de execuções anteriores: faz polling até
            // encontrar a MENSAGEM DESTA rodada (por orderId)
            String traceHeader = null;
            for (int attempt = 0; attempt < 8 && traceHeader == null; attempt++) {
                var messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl)
                                .maxNumberOfMessages(10)
                                .waitTimeSeconds(2)
                                .messageSystemAttributeNames(
                                        software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.AWS_TRACE_HEADER))
                        .messages();
                traceHeader = messages.stream()
                        .filter(m -> m.body().contains(orderId))
                        .map(m -> m.attributes().get(
                                software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.AWS_TRACE_HEADER))
                        .findFirst().orElse(null);
            }
            if (traceHeader == null) {
                throw new IllegalStateException("mensagem de " + orderId + " não encontrada na fila");
            }
            System.out.println("[DEMO] AWSTraceHeader do SQS: " + traceHeader);
            OrderBillingProcessor billing = new OrderBillingProcessor(cfg);
            String billed = billing.handleRequest(
                    Map.of("orderId", orderId, "traceHeader", traceHeader),
                    new DemoContext("req-demo-3", "order-billing"));
            System.out.println("[DEMO] J3 consumidor: " + billed);
        }

        System.out.println("[DEMO] pronto — confira http://127.0.0.1:19877/trace2local "
                + "(3 execuções: COMPLETED, FAILED e a árvore fundida do consumidor)");
        // o flush síncrono do runtime já entregou tudo ao Station
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    static final class DemoContext implements Context {
        private final String requestId;
        private final String functionName;

        DemoContext(String requestId, String functionName) {
            this.requestId = requestId;
            this.functionName = functionName;
        }

        @Override public String getAwsRequestId() { return requestId; }
        @Override public String getLogGroupName() { return "/aws/lambda/demo"; }
        @Override public String getLogStreamName() { return "demo"; }
        @Override public String getFunctionName() { return functionName; }
        @Override public String getFunctionVersion() { return "$LATEST"; }
        @Override public String getInvokedFunctionArn() {
            return "arn:aws:lambda:us-east-1:000000000000:function:" + functionName;
        }
        @Override public CognitoIdentity getIdentity() { return null; }
        @Override public ClientContext getClientContext() { return null; }
        @Override public LambdaLogger getLogger() {
            return new LambdaLogger() {
                @Override public void log(String message) {
                    System.out.println("[LAMBDA:" + functionName + "] " + message);
                }
                @Override public void log(byte[] message) {
                    log(new String(message, java.nio.charset.StandardCharsets.UTF_8));
                }
            };
        }
        @Override public int getMemoryLimitInMB() { return 128; }
        @Override public int getRemainingTimeInMillis() { return 30_000; }
    }
}
