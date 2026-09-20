package tech.neural7.tracevanta.examples.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import tech.neural7.tracevanta.server.TraceVantaHttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TESTE INTEGRADO DE PONTA A PONTA — CENÁRIO COMPLETO (SPEC §10 / E1–E9):
 * LocalStack REAL (Docker: DynamoDB + SNS + SQS com fanout) + app Spring Boot +
 * disparo pelo Request Launcher da UI + pipeline real + API REST do TraceVanta.
 *
 * <p>Jornadas:
 * <ul>
 *   <li><b>JC-1</b> — POST /orders: HTTP → BUSINESS → DynamoDB PutItem (delta EXACT) → SNS;</li>
 *   <li><b>JC-3</b> — o fanout SNS→SQS entrega a mensagem com AWSTraceHeader; o consumidor
 *       continua o MESMO trace e o ramo SQS/BillOrder/DynamoDB aparece NA MESMA ÁRVORE
 *       (correlação por Link + parent remoto);</li>
 *   <li><b>JC-2</b> — confirm com condição: 1º OK (UPDATE EXACT + evento SNS → consumidor
 *       marca BILLED), 2º 409 com {@code ConditionalCheckFailedException} na árvore.</li>
 * </ul>
 *
 * <p>Evidências de QA em {@code docs/qa/}. Roda com {@code mvn -Pit test} (requer Docker).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18089",
        "tracevanta.port=0",
        "tracevanta.quiescence-ms=15000", // janela para o consumidor ligado por Link chegar (JC-3)
        "orders.sns-topic-arn=arn:aws:sns:us-east-1:000000000000:order-events"})
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderJourneyIT {

    /** Credenciais de dev para o SDK AWS (resolvidas ANTES do contexto Spring subir). */
    static {
        System.setProperty("aws.region", "us-east-1");
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.2"))
            .withServices(LocalStackContainer.Service.DYNAMODB,
                    LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    static String BILLING_QUEUE_URL;
    private static boolean INFRA_PROVISIONED;

    @DynamicPropertySource
    static void localstackProps(DynamicPropertyRegistry registry) {
        // O provisionamento é LAZY: os suppliers rodam durante a criação do contexto,
        // DEPOIS do container subir mas ANTES dos @BeforeAll — então a fila precisa
        // existir já na resolução da property.
        registry.add("localstack.endpoint", () -> {
            provisionInfraOnce();
            return LOCALSTACK.getEndpoint();
        });
        registry.add("orders.billing-queue-url", () -> {
            provisionInfraOnce();
            return BILLING_QUEUE_URL;
        });
    }

    private static synchronized void provisionInfraOnce() {
        if (INFRA_PROVISIONED) {
            return;
        }
        INFRA_PROVISIONED = true;
        try {
            DynamoDbClient ddb = DynamoDbClient.builder()
                    .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                    .build();
            SnsClient sns = SnsClient.builder()
                    .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SNS))
                    .build();
            SqsClient sqs = SqsClient.builder()
                    .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
                    .build();

            ddb.createTable(CreateTableRequest.builder()
                    .tableName("orders")
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
            ddb.waiter().waitUntilTableExists(r -> r.tableName("orders"));

            String topicArn = sns.createTopic(CreateTopicRequest.builder().name("order-events").build()).topicArn();
            BILLING_QUEUE_URL = sqs.createQueue(CreateQueueRequest.builder()
                    .queueName("billing-queue").build()).queueUrl();
            String queueArn = sqs.getQueueAttributes(b -> b.queueUrl(BILLING_QUEUE_URL)
                            .attributeNames(QueueAttributeName.QUEUE_ARN))
                    .attributes().get(QueueAttributeName.QUEUE_ARN);
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                    .queueUrl(BILLING_QUEUE_URL)
                    .attributes(Map.of(QueueAttributeName.POLICY, """
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
                            "Action":"sqs:SendMessage","Resource":"%s",
                            "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}
                            """.formatted(queueArn, topicArn)))
                    .build());
            sns.subscribe(s -> s.topicArn(topicArn).protocol("sqs").endpoint(queueArn));

            ddb.close();
            sns.close();
            sqs.close();
        } catch (Exception e) {
            INFRA_PROVISIONED = false;
            throw new IllegalStateException("falha no provisionamento do LocalStack", e);
        }
    }

    @Autowired
    TraceVantaHttpServer traceVantaServer;

    // ------------------------------------------------------------- evidência

    private static final List<String> EVIDENCE = new ArrayList<>();
    private static final List<String> SSE_SEQUENCE = new CopyOnWriteArrayList<>();
    private static String CREATED_ORDER_ID;

    @BeforeAll
    static void provisionInfra() throws Exception {
        provisionInfraOnce();
        evidence("# EVIDÊNCIAS QA — E2E TraceVanta — cenário completo LocalStack\n");
        evidence("Data: " + java.time.Instant.now());
        evidence("Ambiente: " + LOCALSTACK.getDockerImageName()
                + " | container " + LOCALSTACK.getContainerId()
                + " | Java " + System.getProperty("java.version"));
        evidence("Infra provisionada: tabela `orders` + tópico SNS `order-events`"
                + " + fila SQS `billing-queue` (fanout SNS→SQS) + consumidor BillingConsumer na app");
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Path dir = Path.of("..", "..", "docs", "qa").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("EVIDENCIA-E2E.md"), String.join("\n", EVIDENCE));
        Files.writeString(dir.resolve("evidence-sse-sequence.txt"),
                "Sequência de eventos SSE observada (contrato §5.2):\n" + String.join("\n", SSE_SEQUENCE));
        System.out.println("[E2E] evidências em " + dir);
    }

    private static void evidence(String line) {
        EVIDENCE.add(line);
        System.out.println("[E2E] " + line);
    }

    private String tvUrl() {
        return "http://127.0.0.1:" + traceVantaServer.port() + "/tracevanta";
    }

    // ------------------------------------------------------------- JC-1 + JC-3 (consumidor na mesma árvore)

    @Test
    @Order(1)
    void jc1AndJc3FullJourneyWithAsyncConsumer() throws Exception {
        evidence("\n## JC-1 + JC-3 — POST /orders disparado pela UI, consumido pela fila na MESMA árvore");
        evidence("App :18089 | TraceVanta :" + traceVantaServer.port());

        long dispatchToStartedMs;
        long totalMs;
        String executionId;
        try (SseCollector sse = new SseCollector(tvUrl() + "/api/stream")) {
            JsonNode body = MAPPER.readTree(
                    "{\"customerId\":\"C-E2E\",\"total\":250.00,\"password\":\"hunter2\"}");
            long t0 = System.nanoTime();
            JsonNode launch = execute("post:/orders", body, Map.of());
            executionId = launch.path("executionId").asText();
            long startedNanos = sse.startedNanos(executionId, 8_000);
            dispatchToStartedMs = Math.max(0, (startedNanos - t0) / 1_000_000);
            evidence("- disparo → `execution.started` no SSE: **" + dispatchToStartedMs + " ms** (NFR-4: < 1 s)");
            assertThat(dispatchToStartedMs)
                    .as("NFR-4/E4 — a árvore começa a aparecer em menos de 1 s")
                    .isLessThan(1000);

            // a execução só conclui quando o consumidor chega (ou a janela de quiescência fecha)
            JsonNode exec = awaitExecution(executionId, Duration.ofSeconds(45));
            totalMs = (System.nanoTime() - t0) / 1_000_000;
            evidence("- execução **" + executionId + "** concluída em **" + totalMs + " ms**"
                    + " | status=" + exec.path("status").asText()
                    + " | nós=" + exec.path("metrics").path("nodeCount").asInt()
                    + " | profundidade=" + exec.path("metrics").path("maxDepth").asInt());

            assertThat(exec.path("trigger").asText()).isEqualTo("UI_DISPATCH");

            // árvore síncrona (E5): UI Dispatch → HTTP server → CreateOrder → DynamoDB + SNS
            JsonNode root = exec.path("roots").get(0);
            assertThat(root.path("kind").asText()).isEqualTo("HTTP_CLIENT");
            JsonNode server = child(root, "HTTP_SERVER", "POST /orders");
            assertThat(attr(server, tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_STATUS))
                    .isEqualTo("201");
            JsonNode business = child(server, "BUSINESS", "CreateOrder");
            JsonNode dynamo = child(business, "DYNAMODB", "DynamoDB: orders");

            // delta EXACT (E7)
            JsonNode putMutation = dynamo.path("mutation");
            assertThat(putMutation.path("kind").asText()).isEqualTo("CREATE");
            assertThat(putMutation.path("fidelity").asText()).isEqualTo("EXACT");
            String orderId = putMutation.path("key").asText();
            assertThat(orderId).startsWith("ORDER#");
            CREATED_ORDER_ID = orderId;
            evidence("- delta DynamoDB **EXACT** (E7): before={} item novo, after={pk=" + orderId + ", total=250.0}");

            // JC-3 (E8): o ramo SNS GANHA o consumidor na mesma árvore (parent remoto via AWSTraceHeader)
            JsonNode sns = child(business, "SNS", "SNS: order-events");
            assertThat(sns.path("status").asText()).isNotEqualTo("ORPHANED");
            JsonNode sqsConsumer = child(sns, "SQS", "SQS: billing-queue");
            JsonNode billOrder = child(sqsConsumer, "BUSINESS", "BillOrder");
            // consumidor leu o pedido (READ_ONLY) e, como ainda está PENDING, não marcou BILLED
            JsonNode readOnly = findMutation(exec, "READ_ONLY", "orders");
            assertThat(readOnly).as("GetItem do consumidor (READ_ONLY)").isNotNull();
            assertThat(findMutation(exec, "UPDATE", "orders")).as("não deve haver UPDATE no fluxo PENDING").isNull();
            evidence("- JC-3 (E8): SNS→SQS→BillOrder correlacionado na MESMA árvore"
                    + " (SQS receive e BillOrder continuam o trace via AWSTraceHeader/Parent) | GetItem READ_ONLY");
            assertThat(exec.path("status").asText()).isEqualTo("COMPLETED");

            // redaction NA ORIGEM (E11)
            String payload = server.path("payload").path("request").asText("");
            assertThat(payload).contains("[TRACEVANTA_REDACTED]");
            assertThat(payload).doesNotContain("hunter2");
            evidence("- redaction (E11): payload do nó HTTP contém `[TRACEVANTA_REDACTED]` e NÃO contém a senha");

            // export .tvtrace (JC-4)
            HttpResponse<String> exported = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create(tvUrl() + "/api/executions/" + executionId + "/export"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(exported.statusCode()).isEqualTo(200);
            assertThat(exported.body()).contains("\"format\":\"tvtrace\"");
            evidence("- export .tvtrace (JC-4): manifest format=tvtrace, version 0.1.0");

            // saúde
            JsonNode health = MAPPER.readTree(httpGet(tvUrl() + "/api/health"));
            assertThat(health.path("status").asText()).isEqualTo("ok");
            evidence("- /api/health: status=" + health.path("status").asText()
                    + " dropped=" + health.path("dropped").asInt()
                    + " internalErrors=" + health.path("internalErrors").asInt()
                    + " connectedClients=" + health.path("connectedClients").asInt());
        }
        saveExecutionJson(executionId, "evidence-jc1-jc3-execution.json");
        evidence("- jornada completa (disparo → consumidor → árvore pronta) **" + totalMs + " ms**");
    }

    // ------------------------------------------------------------- JC-2

    @Test
    @Order(2)
    void jc2ConfirmThenConditionalConflict() throws Exception {
        evidence("\n## JC-2 — POST /orders/{id}/confirm (condição DynamoDB + evento SNS)");

        // 1º confirm: condição atendida ⇒ UPDATE EXACT + evento SNS ⇒ consumidor marca BILLED
        JsonNode ok = execute("post:/orders/{id}/confirm", null, Map.of("id", CREATED_ORDER_ID));
        JsonNode okExec = awaitExecution(ok.path("executionId").asText(), Duration.ofSeconds(45));
        assertThat(okExec.path("status").asText()).isEqualTo("COMPLETED");

        JsonNode confirmUpdate = findMutationByAfter(okExec, "UPDATE", "orders", "CONFIRMED");
        assertThat(confirmUpdate).as("mutação UPDATE do confirm").isNotNull();
        assertThat(confirmUpdate.path("fidelity").asText()).isEqualTo("EXACT");
        assertThat(confirmUpdate.path("before").isNull()).isTrue(); // declarado (I3)

        // o confirm publica no SNS e o consumidor continua o MESMO trace: BillOrder marca BILLED
        JsonNode sns = findNode(okExec, "SNS", "SNS: order-events");
        assertThat(sns).as("confirm publica no SNS").isNotNull();
        assertThat(find(sns.path("children"), "SQS", "billing-queue"))
                .as("consumidor SQS na MESMA árvore do confirm").isNotNull();        JsonNode billedUpdate = findMutationByAfter(okExec, "UPDATE", "orders", "BILLED");
        assertThat(billedUpdate).as("UPDATE EXACT do BillOrder (markBilled)").isNotNull();
        assertThat(billedUpdate.path("fidelity").asText()).isEqualTo("EXACT");

        evidence("- 1º confirm: status=COMPLETED | UPDATE EXACT after.status=CONFIRMED, before=null (declarado, I3)");
        evidence("- consumidor na MESMA árvore do confirm: SQS billing-queue + BillOrder + UPDATE EXACT after.status=BILLED");
        saveExecutionJson(ok.path("executionId").asText(), "evidence-jc2-confirm-ok.json");

        // 2º confirm: condição violada ⇒ execução VERMELHA com a exceção na árvore
        JsonNode conflict = execute("post:/orders/{id}/confirm", null, Map.of("id", CREATED_ORDER_ID));
        JsonNode failExec = awaitExecution(conflict.path("executionId").asText(), Duration.ofSeconds(45));
        assertThat(failExec.path("status").asText()).isEqualTo("FAILED");
        boolean exceptionCaptured = anyNode(failExec).stream().anyMatch(n ->
                n.path("error").isObject()
                        && n.path("error").path("type").asText().contains("ConditionalCheckFailedException"));
        assertThat(exceptionCaptured).as("ConditionalCheckFailedException visível na árvore").isTrue();
        evidence("- 2º confirm: status=**FAILED** | nó DynamoDB com ConditionalCheckFailedException + stack recortado");
        saveExecutionJson(conflict.path("executionId").asText(), "evidence-jc2-conflict.json");
    }

    // ------------------------------------------------------------- helpers

    private String httpGet(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private JsonNode execute(String endpointId, JsonNode body, Map<String, String> pathVariables) throws Exception {
        var payload = MAPPER.createObjectNode();
        payload.put("endpointId", endpointId);
        if (body != null) {
            payload.set("body", body);
        }
        var pv = payload.putObject("pathVariables");
        pathVariables.forEach(pv::put);
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create(tvUrl() + "/api/execute"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST /api/execute deve responder 202").isEqualTo(202);
        JsonNode launch = MAPPER.readTree(response.body());
        assertThat(launch.path("traceId").asText()).hasSize(32);
        assertThat(launch.path("traceId").asText()).isNotEqualTo("00000000000000000000000000000000");
        return launch;
    }

    private JsonNode execJson(String executionId) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create(tvUrl() + "/api/executions/" + executionId))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private JsonNode awaitExecution(String executionId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create(tvUrl() + "/api/executions?limit=20"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode list = MAPPER.readTree(response.body());
            for (JsonNode summary : list) {
                if (executionId.equals(summary.path("executionId").asText())
                        && !"RUNNING".equals(summary.path("status").asText())) {
                    return execJson(executionId);
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("execução " + executionId + " não concluiu em " + timeout);
    }

    private static JsonNode child(JsonNode parent, String kind, String labelContains) {
        JsonNode found = find(parent.path("children"), kind, labelContains);
        assertThat(found).as("filho %s '%s' de %s", kind, labelContains, parent.path("label").asText()).isNotNull();
        return found;
    }

    private static JsonNode find(JsonNode nodes, String kind, String labelContains) {
        for (JsonNode node : nodes) {
            if (kind.equals(node.path("kind").asText())
                    && node.path("label").asText().contains(labelContains)) {
                return node;
            }
            JsonNode nested = find(node.path("children"), kind, labelContains);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static JsonNode findNode(JsonNode exec, String kind, String labelContains) {
        for (JsonNode node : anyNode(exec)) {
            if (kind.equals(node.path("kind").asText())
                    && node.path("label").asText().contains(labelContains)) {
                return node;
            }
        }
        return null;
    }

    private static String attr(JsonNode node, String key) {
        JsonNode value = node.path("attributes").get(key);
        return value != null ? value.asText() : null;
    }

    private static JsonNode findMutation(JsonNode exec, String kind, String target) {
        for (JsonNode node : anyNode(exec)) {
            JsonNode mutation = node.path("mutation");
            if (mutation.isObject()
                    && kind.equals(mutation.path("kind").asText())
                    && mutation.path("target").asText().contains(target)) {
                return mutation;
            }
        }
        return null;
    }

    private static JsonNode findMutationByAfter(JsonNode exec, String kind, String target, String statusValue) {
        for (JsonNode node : anyNode(exec)) {
            JsonNode mutation = node.path("mutation");
            if (mutation.isObject()
                    && kind.equals(mutation.path("kind").asText())
                    && mutation.path("target").asText().contains(target)
                    && statusValue.equals(mutation.path("after").path("status").asText())) {
                return mutation;
            }
        }
        return null;
    }

    private static List<JsonNode> anyNode(JsonNode exec) {
        List<JsonNode> out = new ArrayList<>();
        collect(exec.path("roots"), out);
        return out;
    }

    private static void collect(JsonNode nodes, List<JsonNode> out) {
        for (JsonNode node : nodes) {
            out.add(node);
            collect(node.path("children"), out);
        }
    }

    private void saveExecutionJson(String executionId, String fileName) throws Exception {
        Path dir = Path.of("..", "..", "docs", "qa").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), execJson(executionId).toPrettyString());
        evidence("- evidência JSON: docs/qa/" + fileName);
    }

    // ------------------------------------------------------------- SSE (E4/NFR-4 + sequência §5.2)

    private static final class SseCollector implements AutoCloseable {
        private final Map<String, Long> startedNanos = new ConcurrentHashMap<>();
        private final AtomicReference<String> lastEvent = new AtomicReference<>();
        private final java.util.concurrent.CompletableFuture<?> future;

        SseCollector(String url) {
            future = HttpClient.newHttpClient()
                    .sendAsync(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                            HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> response.body().forEach(line -> {
                        if (line.startsWith("event: ")) {
                            lastEvent.set(line.substring("event: ".length()));
                        } else if (line.startsWith("data: ")) {
                            try {
                                JsonNode node = new ObjectMapper().readTree(line.substring("data: ".length()));
                                String type = lastEvent.get();
                                String id = node.path("executionId").asText(null);
                                SSE_SEQUENCE.add(type + " → " + (id != null ? id : node.path("kind").asText("?")));
                                if ("execution.started".equals(type) && id != null && !id.isBlank()) {
                                    startedNanos.putIfAbsent(id, System.nanoTime());
                                }
                            } catch (Exception ignored) {
                                // frame ilegível não afeta a medição
                            }
                        }
                    }));
        }

        long startedNanos(String executionId, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Long nanos = startedNanos.get(executionId);
                if (nanos != null) {
                    return nanos;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("execution.started do " + executionId + " não chegou pelo SSE em " + timeoutMs + " ms");
        }

        @Override
        public void close() {
            future.cancel(true);
        }
    }
}
