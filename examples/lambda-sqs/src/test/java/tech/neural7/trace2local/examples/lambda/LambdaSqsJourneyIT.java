package tech.neural7.trace2local.examples.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.sqs.SqsClient;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.server.Trace2LocalHttpServer;
import tech.neural7.trace2local.server.Trace2LocalMeta;
import tech.neural7.trace2local.station.MutationIngestReceiver;
import tech.neural7.trace2local.station.OtlpTraceReceiver;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Jornadas ponta a ponta do projeto de teste/validação: Lambda + DynamoDB + SQS
 * no LocalStack REAL (Testcontainers), configurado com a lib Trace2Local.
 *
 * <p><b>J1 — jornada feliz:</b> o runtime Lambda ({@code Trace2LocalLambdaRuntime},
 * modo Companion — ADR-002) abre o span raiz SERVER com {@code faas.name} (→ nó
 * LAMBDA), o handler grava no DynamoDB (delta EXACT — ADR-003) e publica no SQS.
 * A árvore no Station: LAMBDA → DYNAMODB + SQS, trigger LAMBDA_EVENT.
 *
 * <p><b>J2 — jornada de erro:</b> o handler lança após o PutItem — a execução
 * fecha FAILED com a raiz vermelha e o ramo DynamoDB OK (sucesso parcial visível).
 *
 * <p><b>J3 — continuação do consumidor (o "JC-3" do mundo Lambda, SPEC §4.11):</b>
 * o SendMessage carrega o {@code AWSTraceHeader} (gerado pelo OTel); o
 * {@code OrderBillingProcessor} devolve o parent remoto via
 * {@link tech.neural7.trace2local.lambda.Trace2LocalLambdaHandler#remoteParentOf}
 * e a continuação aparece NA MESMA ÁRVORE: LAMBDA → SQS → LAMBDA → DYNAMODB
 * (UPDATE), com identidade de execução estável (id do produtor).
 *
 * <p>Evidências: {@code docs/qa/evidence-lambda-sqs*.json}.
 */
@Testcontainers
class LambdaSqsJourneyIT {

    private static final int STATION_PORT = 19877;
    private static final String STATION_URL = "http://127.0.0.1:" + STATION_PORT;

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
            .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.SQS);

    // ---- Station em processo (modo Companion: OTLP + mutações) ----
    private static Trace2LocalPipeline stationPipeline;
    private static Trace2LocalHttpServer station;

    // ---- clientes CRUS: provisionamento e asserts fora da instrumentação ----
    private static DynamoDbClient ddb;
    private static SqsClient sqs;
    private static String queueUrl;

    private static synchronized String stationUrl() {
        if (station == null) {
            Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                    .port(STATION_PORT)
                    .quiescenceMs(4000) // janela para o consumidor da J3 chegar na MESMA árvore
                    .retentionMaxExecutions(50)
                    .build();
            stationPipeline = Trace2LocalPipeline.start(cfg);
            station = Trace2LocalHttpServer.builder(cfg, stationPipeline)
                    .meta(() -> Trace2LocalMeta.station())
                    .endpoints(List::of) // Companion: catálogo vazio, observação pura (§4.8)
                    .extraRoute("/v1/traces", new OtlpTraceReceiver(stationPipeline, cfg))
                    .extraRoute("/t2lingest/v1/mutations", new MutationIngestReceiver(stationPipeline))
                    .build();
            station.start();
            System.out.println("[E2E-LAMBDA] Station em " + STATION_URL
                    + " — OTLP em /v1/traces, mutações em /t2lingest/v1/mutations");
        }
        return STATION_URL;
    }

    @BeforeAll
    static void provisionInfra() {
        stationUrl();
        ddb = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .build();
        sqs = SqsClient.builder()
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
        queueUrl = sqs.createQueue(r -> r.queueName("orders-queue")).queueUrl();
        System.out.println("[E2E-LAMBDA] Infra provisionada: tabela `orders` + fila `orders-queue` ("
                + LOCALSTACK.getEndpoint() + ")");
    }

    @BeforeEach
    void setLocalstackEndpoint() {
        System.setProperty("localstack.endpoint",
                LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString());
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) ddb.close();
        if (sqs != null) sqs.close();
        if (station != null) station.close();
        if (stationPipeline != null) stationPipeline.close();
    }

    private static Trace2LocalConfig lambdaCfg() {
        return Trace2LocalConfig.builder()
                .stationEndpoint(STATION_URL)
                .flushTimeoutMs(1500)
                .build();
    }

    private static Context context(String requestId, String functionName) {
        Context ctx = mock(Context.class);
        when(ctx.getFunctionName()).thenReturn(functionName);
        when(ctx.getAwsRequestId()).thenReturn(requestId);
        when(ctx.getInvokedFunctionArn()).thenReturn(
                "arn:aws:lambda:us-east-1:000000000000:function:" + functionName);
        return ctx;
    }

    // ------------------------------------------------------------------ J1

    @Test
    void happyJourneyWritesDynamoPublishesSqsAndLandsOnStation() throws Exception {
        OrderProcessor processor = new OrderProcessor(lambdaCfg());
        Context ctx = context("req-lambda-it-1", "order-processor");

        long t0 = System.nanoTime();
        String result = processor.handleRequest(
                Map.of("orderId", "ORDER-L1", "customerId", "C-LAMBDA", "total", "123.45"), ctx);
        assertThat(result).contains("ORDER-L1");

        // 1) efeito REAL no DynamoDB
        var item = ddb.getItem(r -> r.tableName("orders")
                        .key(Map.of("pk", AttributeValue.fromS("ORDER-L1")))).item();
        assertThat(item).as("item real gravado no DynamoDB").isNotNull();
        assertThat(item.get("customerId").s()).isEqualTo("C-LAMBDA");

        // 2) mensagem REAL na fila SQS
        var messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)).messages();
        assertThat(messages).as("mensagem publicada no SQS")
                .anySatisfy(m -> assertThat(m.body()).contains("ORDER-L1"));

        // 3) árvore no Station: LAMBDA → DynamoDB (delta EXACT) + SQS
        JsonNode exec = awaitExecutionById("req-lambda-it-1", Duration.ofSeconds(30));
        assertThat(exec.path("trigger").asText()).isEqualTo("LAMBDA_EVENT");
        assertThat(exec.path("duration").asDouble()).isGreaterThanOrEqualTo(0);

        JsonNode root = exec.path("roots").get(0);
        assertThat(root.path("kind").asText()).as("raiz é um nó LAMBDA").isEqualTo("LAMBDA");
        assertThat(root.path("label").asText()).isEqualTo("order-processor");

        JsonNode dynamo = findNode(exec, "DYNAMODB", "DynamoDB: orders");
        assertThat(dynamo).as("nó DynamoDB na árvore").isNotNull();
        assertThat(dynamo.path("mutation").path("fidelity").asText()).isEqualTo("EXACT");
        assertThat(dynamo.path("mutation").path("kind").asText()).isEqualTo("CREATE");
        assertThat(findNode(exec, "SQS", "SQS: orders-queue")).as("nó SQS").isNotNull();

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[E2E-LAMBDA] J1 " + exec.path("executionId").asText()
                + " em " + totalMs + " ms | trigger=LAMBDA_EVENT | nós="
                + exec.path("metrics").path("nodeCount").asInt() + " | delta EXACT | dur="
                + exec.path("duration").asDouble() + " ms");

        Path dir = Path.of("..", "..", "docs", "qa").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("evidence-lambda-sqs.json"), exec.toPrettyString());
    }

    // ------------------------------------------------------------------ J2

    @Test
    void failureJourneyMarksTheExecutionRedWithPartialSuccess() throws Exception {
        OrderProcessor processor = new OrderProcessor(lambdaCfg());
        Context ctx = context("req-lambda-it-2", "order-processor");

        assertThatThrownBy(() -> processor.handleRequest(
                Map.of("orderId", "ORDER-L2", "customerId", "C-FAIL", "total", "5000.00", "fail", "true"),
                ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limite de crédito");

        // o PutItem aconteceu ANTES da recusa: sucesso parcial real
        var item = ddb.getItem(r -> r.tableName("orders")
                        .key(Map.of("pk", AttributeValue.fromS("ORDER-L2")))).item();
        assertThat(item).as("PutItem persistiu antes da falha").isNotNull();

        JsonNode exec = awaitExecutionById("req-lambda-it-2", Duration.ofSeconds(30));
        assertThat(exec.path("status").asText()).isEqualTo("FAILED");

        JsonNode root = exec.path("roots").get(0);
        assertThat(root.path("status").asText()).isEqualTo("ERROR");
        assertThat(root.path("error").path("message").asText()).contains("limite de crédito");

        // sucesso parcial visível: o ramo DynamoDB OK sob a raiz vermelha
        JsonNode dynamo = findNode(exec, "DYNAMODB", "DynamoDB: orders");
        assertThat(dynamo).isNotNull();
        assertThat(dynamo.path("status").asText()).isEqualTo("OK");
        assertThat(dynamo.path("mutation").path("kind").asText()).isEqualTo("CREATE");
        assertThat(findNode(exec, "SQS", "orders-queue")).as("SQS não enviado após a falha").isNull();

        System.out.println("[E2E-LAMBDA] J2 execução vermelha com sucesso parcial (DynamoDB OK) — FAILED");
        Path dir = Path.of("..", "..", "docs", "qa").toAbsolutePath().normalize();
        Files.writeString(dir.resolve("evidence-lambda-sqs-failure.json"), exec.toPrettyString());
    }

    // ------------------------------------------------------------------ J3

    @Test
    void sqsConsumerContinuesTheSameTreeViaRemoteParent() throws Exception {
        OrderProcessor processor = new OrderProcessor(lambdaCfg());
        Context producerCtx = context("req-lambda-it-3", "order-processor");
        processor.handleRequest(
                Map.of("orderId", "ORDER-L3", "customerId", "C-J3", "total", "77.70"), producerCtx);

        // a mensagem REAL do SQS carrega o AWSTraceHeader gerado pelo OTel no
        // SendMessage — no SQS é ATRIBUTO DE SISTEMA (systemAttributes), que só
        // vem na resposta se for pedido explicitamente
        var message = sqs.receiveMessage(r -> r.queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .messageSystemAttributeNames(software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.AWS_TRACE_HEADER))
                .messages().stream()
                .filter(m -> m.body().contains("ORDER-L3"))
                .findFirst().orElseThrow();
        String traceHeader = message.attributes()
                .get(software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.AWS_TRACE_HEADER);
        assertThat(traceHeader).as("AWSTraceHeader injetado pelo OTel no SQS send")
                .startsWith("Root=");

        // consumidor: o parent remoto devolvido por remoteParentOf liga a continuação
        OrderBillingProcessor billing = new OrderBillingProcessor(lambdaCfg());
        Context consumerCtx = context("req-lambda-it-4", "order-billing");
        String result = billing.handleRequest(
                Map.of("orderId", "ORDER-L3", "traceHeader", traceHeader), consumerCtx);
        assertThat(result).contains("billed");

        // item marcado BILLED de verdade
        var item = ddb.getItem(r -> r.tableName("orders")
                        .key(Map.of("pk", AttributeValue.fromS("ORDER-L3")))).item();
        assertThat(item.get("status").s()).isEqualTo("BILLED");

        // UMA árvore: identidade estável do produtor + continuação sob o nó SQS
        JsonNode exec = awaitExecutionById("req-lambda-it-3", Duration.ofSeconds(30));
        assertThat(exec.path("executionId").asText()).isEqualTo("req-lambda-it-3");

        JsonNode sqsNode = findNode(exec, "SQS", "SQS: orders-queue");
        assertThat(sqsNode).isNotNull();
        assertThat(sqsNode.path("status").asText()).as("SQS consumido: não é mais ramo morto")
                .isEqualTo("OK");
        JsonNode consumer = findChild(sqsNode, "LAMBDA", "order-billing");
        assertThat(consumer).as("nó LAMBDA do consumidor NA MESMA árvore").isNotNull();
        JsonNode update = findChild(consumer, "DYNAMODB", "DynamoDB: orders");
        assertThat(update).as("update do consumidor sob o ramo SQS").isNotNull();
        assertThat(update.path("mutation").path("kind").asText()).isEqualTo("UPDATE");
        assertThat(update.path("mutation").path("fidelity").asText()).isEqualTo("EXACT");
        // read-back (§4.10 fechada): before do ALL_OLD + after exato da releitura
        assertThat(update.path("mutation").path("before").isObject()).as("before presente").isTrue();
        assertThat(update.path("mutation").path("before").path("pk").asText()).isEqualTo("ORDER-L3");
        assertThat(update.path("mutation").path("after").path("status").asText()).isEqualTo("BILLED");
        assertThat(update.path("mutation").path("deltas").toString()).contains("status");

        int nodeCount = exec.path("metrics").path("nodeCount").asInt();
        assertThat(nodeCount).isEqualTo(5); // LAMBDA + DYNAMODB + SQS + LAMBDA + DYNAMODB
        System.out.println("[E2E-LAMBDA] J3 continuação SQS→Lambda NA MESMA árvore — " + nodeCount
                + " nós, execução " + exec.path("executionId").asText());

        Path dir = Path.of("..", "..", "docs", "qa").toAbsolutePath().normalize();
        Files.writeString(dir.resolve("evidence-lambda-sqs-consumer.json"), exec.toPrettyString());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode awaitExecutionById(String executionId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create(STATION_URL + "/trace2local/api/executions?limit=50"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode list = MAPPER.readTree(response.body());
            for (JsonNode summary : list) {
                if (!executionId.equals(summary.path("executionId").asText())) {
                    continue;
                }
                if ("RUNNING".equals(summary.path("status").asText())) {
                    break; // ainda montando — aguarda
                }
                HttpResponse<String> full = HTTP.send(HttpRequest.newBuilder()
                                .uri(URI.create(STATION_URL + "/trace2local/api/executions/" + executionId))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return MAPPER.readTree(full.body());
            }
            Thread.sleep(300);
        }
        throw new AssertionError("execução " + executionId + " não apareceu no Station em " + timeout);
    }

    private static JsonNode findNode(JsonNode exec, String kind, String labelContains) {
        List<JsonNode> nodes = new ArrayList<>();
        collect(exec.path("roots"), nodes);
        return nodes.stream()
                .filter(n -> kind.equals(n.path("kind").asText())
                        && n.path("label").asText().contains(labelContains))
                .findFirst().orElse(null);
    }

    private static JsonNode findChild(JsonNode parent, String kind, String labelContains) {
        List<JsonNode> nodes = new ArrayList<>();
        collect(parent.path("children"), nodes);
        return nodes.stream()
                .filter(n -> kind.equals(n.path("kind").asText())
                        && n.path("label").asText().contains(labelContains))
                .findFirst().orElse(null);
    }

    private static void collect(JsonNode nodes, List<JsonNode> out) {
        for (JsonNode node : nodes) {
            out.add(node);
            collect(node.path("children"), out);
        }
    }
}
