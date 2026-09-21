package tech.neural7.trace2local.examples.lambda;

import tech.neural7.trace2local.config.Trace2LocalConfig;

import java.util.Map;
import java.util.UUID;

/**
 * Runner de demonstração do monitoramento de idempotência: três chamadas
 * (criar → duplicado recusado → criar com chave nova) contra o LocalStack do
 * compose e o Station em :19877, para captura de telas e evidência visual.
 *
 * <pre>
 * java -cp target/lambda-sqs-bundle.jar;<m2>/aws-lambda-java-core-1.4.0.jar \
 *   -Dlocalstack.endpoint=http://localhost:4567 \
 *   -Dtrace2local.station.endpoint=http://127.0.0.1:19877 \
 *   -Dtrace2local.station.token=devtoken \
 *   tech.neural7.trace2local.examples.lambda.IdempotencyDemoRun
 * </pre>
 */
public final class IdempotencyDemoRun {

    private IdempotencyDemoRun() {}

    public static void main(String[] args) throws Exception {
        Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                .stationEndpoint(System.getProperty("trace2local.station.endpoint",
                        env("TRACE2LOCAL_STATION_ENDPOINT", "http://127.0.0.1:19877")))
                .stationToken(System.getProperty("trace2local.station.token",
                        env("TRACE2LOCAL_STATION_TOKEN", null)))
                .flushTimeoutMs(1500)
                .build();

        String key = "IDEM-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        IdempotentProcessor processor = new IdempotentProcessor(cfg);

        String first = processor.handleRequest(
                Map.of("idempotencyKey", key, "payload", "{\"amount\":100}"),
                new LambdaSqsDemoRun.DemoContext("req-idem-demo-1", "idempotent-processor"));
        System.out.println("[DEMO-IDEM] 1ª chamada (cria): " + first);

        String duplicate = processor.handleRequest(
                Map.of("idempotencyKey", key, "payload", "{\"amount\":100}"),
                new LambdaSqsDemoRun.DemoContext("req-idem-demo-2", "idempotent-processor"));
        System.out.println("[DEMO-IDEM] 2ª chamada (duplicado): " + duplicate);

        String third = processor.handleRequest(
                Map.of("idempotencyKey", key + "-B", "payload", "{\"amount\":200}"),
                new LambdaSqsDemoRun.DemoContext("req-idem-demo-3", "idempotent-processor"));
        System.out.println("[DEMO-IDEM] 3ª chamada (chave nova): " + third);

        System.out.println("[DEMO-IDEM] pronto — confira http://127.0.0.1:19877/trace2local "
                + "(execução FAILED com IdempotencyGuard OK + DynamoDB ERROR sem delta)");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
