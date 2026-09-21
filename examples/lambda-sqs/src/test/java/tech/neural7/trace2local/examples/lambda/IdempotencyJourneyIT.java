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
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E2E de MONITORAMENTO DE IDEMPOTÊNCIA (LocalStack real + Station em processo):
 *
 * <ol>
 *   <li>1ª invocação com chave nova → {@code created}, árvore
 *       {@code LAMBDA → IdempotencyGuard → DynamoDB (CREATE/EXACT)};</li>
 *   <li>2ª invocação com a MESMA chave → {@code duplicate_ignored}, SEM efeito
 *       colateral (contagem no DynamoDB não muda) — e o canvas conta a
 *       história: guarda OK + nó DynamoDB VERMELHO com
 *       {@code ConditionalCheckFailedException} e SEM delta;</li>
 *   <li>3ª invocação com chave nova → {@code created} de novo (a guarda só
 *       bloqueia duplicados).</li>
 * </ol>
 *
 * Semântica documentada: execução com nó ERROR fecha FAILED (invariante do
 * assembler) — para o monitor é o sinal correto de "escrita recusada".
 * Evidências: {@code docs/qa/evidence-idempotency-*.json}.
 */
@Testcontainers
class IdempotencyJourneyIT {

    private static final int STATION_PORT = 19878;
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
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private static Trace2LocalPipeline stationPipeline;
    private static Trace2LocalHttpServer station;
    private static DynamoDbClient ddb; // cru: provisionamento + verificação de efeito colateral

    private static synchronized void startStation() {
        if (station == null) {
            Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                    .port(STATION_PORT)
                    .quiescenceMs(2500)
                    .retentionMaxExecutions(50)
                    .build();
            stationPipeline = Trace2LocalPipeline.start(cfg);
            station = Trace2LocalHttpServer.builder(cfg, stationPipeline)
                    .meta(() -> Trace2LocalMeta.station())
                    .endpoints(List::of)
                    .extraRoute("/v1/traces", new OtlpTraceReceiver(stationPipeline, cfg))
                    .extraRoute("/t2lingest/v1/mutations", new MutationIngestReceiver(stationPipeline))
                    .build();
            station.start();
            System.out.println("[E2E-IDEM] Station em " + STATION_URL);
        }
    }

    @BeforeAll
    static void provisionInfra() {
        startStation();
        ddb = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .build();
        ddb.createTable(CreateTableRequest.builder()
                .tableName(IdempotentProcessor.TABLE)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        ddb.waiter().waitUntilTableExists(r -> r.tableName(IdempotentProcessor.TABLE));
        System.out.println("[E2E-IDEM] tabela `" + IdempotentProcessor.TABLE + "` provisionada");
    }

    @BeforeEach
    void setLocalstackEndpoint() {
        System.setProperty("localstack.endpoint",
                LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString());
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) ddb.close();
        if (station != null) station.close();
        if (stationPipeline != null) stationPipeline.close();
    }

    @Test
    void duplicateRequestIsRejectedWithoutSideEffectAndTheCanvasTellsTheStory() throws Exception {
        IdempotentProcessor processor = new IdempotentProcessor(Trace2LocalConfig.builder()
                .stationEndpoint(STATION_URL)
                .flushTimeoutMs(1500)
                .build());

        // 1) primeira chamada: cria
        String created1 = processor.handleRequest(
                Map.of("idempotencyKey", "IDEM-1", "payload", "{\"amount\":100}"),
                context("req-idem-1"));
        assertThat(created1).contains("\"created\"");

        // 2) DUPLICADO da mesma chave: recusado pela guarda — sem efeito colateral
        String duplicate = processor.handleRequest(
                Map.of("idempotencyKey", "IDEM-1", "payload", "{\"amount\":100}"),
                context("req-idem-2"));
        assertThat(duplicate).contains("\"duplicate_ignored\"");

        // 3) chave nova: cria normalmente (a guarda só bloqueia duplicados)
        String created2 = processor.handleRequest(
                Map.of("idempotencyKey", "IDEM-2", "payload", "{\"amount\":200}"),
                context("req-idem-3"));
        assertThat(created2).contains("\"created\"");

        // ---- PROVA DE IDEMPOTÊNCIA NO ESTADO: exatamente 2 itens, nada de triplicado
        var items = ddb.scan(r -> r.tableName(IdempotentProcessor.TABLE)).items();
        assertThat(items).as("3 chamadas, 2 chaves distintas → 2 itens (duplicado não escreveu nada)")
                .hasSize(2);

        // ---- MONITORAMENTO: a árvore da 1ª chamada mostra a criação
        JsonNode execCreated = awaitExecutionById("req-idem-1", Duration.ofSeconds(30));
        assertThat(execCreated.path("status").asText()).isEqualTo("COMPLETED");
        JsonNode guard1 = findNode(execCreated, "BUSINESS", "IdempotencyGuard");
        assertThat(guard1).as("nó BUSINESS da guarda").isNotNull();
        assertThat(guard1.path("status").asText()).isEqualTo("OK");
        JsonNode dynamo1 = findNode(execCreated, "DYNAMODB", "DynamoDB: " + IdempotentProcessor.TABLE);
        assertThat(dynamo1.path("status").asText()).isEqualTo("OK");
        assertThat(dynamo1.path("mutation").path("kind").asText()).isEqualTo("CREATE");
        assertThat(dynamo1.path("mutation").path("fidelity").asText()).isEqualTo("EXACT");

        // ---- MONITORAMENTO: a árvore do DUPLICADO conta a história da recusa
        JsonNode execDup = awaitExecutionById("req-idem-2", Duration.ofSeconds(30));
        assertThat(execDup.path("status").asText())
                .as("nó ERROR fecha FAILED — para o monitor é o sinal de escrita recusada")
                .isEqualTo("FAILED");
        JsonNode root = execDup.path("roots").get(0);
        assertThat(root.path("kind").asText()).isEqualTo("LAMBDA");
        assertThat(root.path("status").asText()).as("a invocação em si terminou OK (recusa é o comportamento esperado)")
                .isEqualTo("OK");
        JsonNode guardDup = findNode(execDup, "BUSINESS", "IdempotencyGuard");
        assertThat(guardDup.path("status").asText()).as("a guarda cumpriu o papel").isEqualTo("OK");
        JsonNode dynamoDup = findNode(execDup, "DYNAMODB", "DynamoDB: " + IdempotentProcessor.TABLE);
        assertThat(dynamoDup.path("status").asText()).as("escrita condicional rejeitada").isEqualTo("ERROR");
        assertThat(dynamoDup.path("error").path("type").asText())
                .as("tipo da exceção (do evento exception do OTLP)")
                .contains("ConditionalCheckFailed");
        assertThat(dynamoDup.path("error").path("message").asText().toLowerCase())
                .contains("conditional request failed");
        assertThat(dynamoDup.path("mutation").isMissingNode() || dynamoDup.path("mutation").isNull())
                .as("SEM delta na tentativa duplicada — nada foi escrito (a prova visual da idempotência)")
                .isTrue();

        // ---- STORYTELLING: narrativa de negócio (contexto + glossário do classpath)
        HttpResponse<String> storyResponse = HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create(STATION_URL + "/trace2local/api/executions/req-idem-2/story"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(storyResponse.statusCode()).isEqualTo(200);
        JsonNode story = MAPPER.readTree(storyResponse.body());
        assertThat(story.path("steps").size()).isEqualTo(3);
        assertThat(story.toString()).as("glossário aplicado à nota da guarda")
                .contains("Guarda de idempotência");
        assertThat(story.path("conclusion").asText()).contains("com erro");

        // evidências
        Path dir = Path.of("..", "..", "docs", "qa").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("evidence-idempotency-created.json"), execCreated.toPrettyString());
        Files.writeString(dir.resolve("evidence-idempotency-duplicate.json"), execDup.toPrettyString());
        System.out.println("[E2E-IDEM] 3 chamadas → 2 itens no DynamoDB; árvore do duplicado: "
                + "LAMBDA OK → IdempotencyGuard OK → DynamoDB ERROR (ConditionalCheckFailed) sem delta — "
                + "evidências em docs/qa/evidence-idempotency-*.json");
    }

    // ------------------------------------------------------------------ helpers

    private static Context context(String requestId) {
        Context ctx = mock(Context.class);
        when(ctx.getFunctionName()).thenReturn("idempotent-processor");
        when(ctx.getAwsRequestId()).thenReturn(requestId);
        when(ctx.getInvokedFunctionArn()).thenReturn(
                "arn:aws:lambda:us-east-1:000000000000:function:idempotent-processor");
        return ctx;
    }

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
                    break;
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

    private static void collect(JsonNode nodes, List<JsonNode> out) {
        for (JsonNode node : nodes) {
            out.add(node);
            collect(node.path("children"), out);
        }
    }
}
