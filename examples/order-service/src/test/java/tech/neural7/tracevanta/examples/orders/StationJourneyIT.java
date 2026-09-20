package tech.neural7.tracevanta.examples.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;
import tech.neural7.tracevanta.server.TraceVantaHttpServer;
import tech.neural7.tracevanta.server.TraceVantaMeta;
import tech.neural7.tracevanta.station.MutationIngestReceiver;
import tech.neural7.tracevanta.station.OtlpTraceReceiver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MODO COMPANION (ADR-002 / §4.2 / SPEC E2) — validação ponta a ponta:
 * a app roda SEM servidor embedded (modo detectado por tracevanta.station.endpoint),
 * exporta OTLP/HTTP para o Station, e a árvore aparece na UI do Station —
 * SEM delta de dados (o canal de mutação não tem equivalente OTLP: degradação
 * prevista, não erro — SPEC §5.3).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18090",
        "orders.sns-topic-arn=arn:aws:sns:us-east-1:000000000000:order-events",
        "orders.billing-queue-url=",
        "tracevanta.quiescence-ms=5000"})
@org.springframework.test.context.TestPropertySource(properties = {
        // porta FIXA: o binding de @ConfigurationProperties acontece antes da
        // resolução de suppliers dinâmicos (@DynamicPropertySource)
        "tracevanta.station.endpoint=http://127.0.0.1:19876"})
@ActiveProfiles("dev")
class StationJourneyIT {

    private static final int STATION_PORT = 19876;

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
            .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.SNS);

    // ---- Station (processo de longa duração do modo Companion) ----
    private static TraceVantaPipeline stationPipeline;
    private static TraceVantaHttpServer station;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("localstack.endpoint", LOCALSTACK::getEndpoint);
    }

    private static synchronized String stationUrl() {
        if (station == null) {
            TraceVantaConfig cfg = TraceVantaConfig.builder()
                    .port(STATION_PORT)
                    .quiescenceMs(2000)
                    .retentionMaxExecutions(50)
                    .build();
            stationPipeline = TraceVantaPipeline.start(cfg);
            station = TraceVantaHttpServer.builder(cfg, stationPipeline)
                    .meta(() -> TraceVantaMeta.station())
                    .endpoints(List::of) // Companion: catálogo vazio — observação pura (§4.8)
                    .extraRoute("/v1/traces", new OtlpTraceReceiver(stationPipeline, cfg))
                    .extraRoute("/tvingest/v1/mutations", new MutationIngestReceiver(stationPipeline))
                    .build();
            station.start();
            System.out.println("[E2E-STATION] Station em http://127.0.0.1:" + station.port()
                    + " — OTLP em /v1/traces");
        }
        return "http://127.0.0.1:" + STATION_PORT;
    }

    @Autowired
    tech.neural7.tracevanta.config.TraceVantaConfig traceVantaConfig;

    @BeforeAll
    static void provisionInfra() throws Exception {
        DynamoDbClient ddb = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .build();
        SnsClient sns = SnsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SNS))
                .build();
        ddb.createTable(CreateTableRequest.builder()
                .tableName("orders")
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        ddb.waiter().waitUntilTableExists(r -> r.tableName("orders"));
        sns.createTopic(CreateTopicRequest.builder().name("order-events").build());
        ddb.close();
        sns.close();
        stationUrl(); // sobe o Station antes do teste (exporter OTLP aponta para a porta fixa)
    }

    @AfterAll
    static void tearDown() {
        if (station != null) {
            station.close();
        }
        if (stationPipeline != null) {
            stationPipeline.close();
        }
    }

    @Test
    void companionModeDeliversTheTreeToTheStation() throws Exception {
        // a app em modo Companion NÃO sobe o servidor embedded: o modo é DETECTADO
        // por tracevanta.station.endpoint (ADR-002, regra 3) — o endpoint do Station
        // está configurado e o SDK exporta OTLP para ele
        assertThat(traceVantaConfig.stationEndpoint()).isNotBlank();

        // disparo direto na app (no modo Companion não há launcher de UI — o dev usa o próprio fluxo)
        long t0 = System.nanoTime();
        HttpResponse<String> appResponse = HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:18090/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"customerId\":\"C-COMPANION\",\"total\":99.90}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(appResponse.statusCode()).isEqualTo(201);
        String orderId = MAPPER.readTree(appResponse.body()).path("orderId").asText();
        assertThat(orderId).startsWith("ORDER#");

        // a árvore aparece no STATION (BatchSpanProcessor → OTLP/HTTP → /v1/traces → assembler)
        JsonNode exec = awaitStationExecution(orderId, Duration.ofSeconds(40));
        assertThat(exec.path("status").asText()).isIn("COMPLETED", "PARTIAL");
        assertThat(exec.path("trigger").asText()).isEqualTo("EXTERNAL");

        JsonNode root = exec.path("roots").get(0);
        assertThat(root.path("kind").asText()).isIn("HTTP_SERVER", "UNKNOWN");
        JsonNode business = findNode(exec, "BUSINESS", "CreateOrder");
        assertThat(business).as("nó BUSINESS CreateOrder").isNotNull();
        JsonNode dynamo = findNode(exec, "DYNAMODB", "DynamoDB: orders");
        if (dynamo == null) {
            // diagnóstico honesto: mostra o que de fato chegou ao Station
            List<JsonNode> all = new ArrayList<>();
            collect(exec.path("roots"), all);
            System.out.println("[E2E-STATION] nós recebidos: "
                    + all.stream().map(n -> n.path("kind").asText() + ":" + n.path("label").asText())
                    .toList());
        }
        assertThat(dynamo).as("nó DynamoDB").isNotNull();
        // DEGRADAÇÃO PREVISTA (§5.3): OTLP não carrega o canal de mutação — sem delta
        assertThat(dynamo.path("mutation").isNull() || dynamo.path("mutation").isMissingNode())
                .as("delta ausente no modo Companion (degradação prevista, não erro)").isTrue();
        JsonNode sns = findNode(exec, "SNS", "SNS: order-events");
        assertThat(sns).as("nó SNS").isNotNull();
        assertThat(sns.path("status").asText()).isEqualTo("ORPHANED");

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[E2E-STATION] execução no Station em " + totalMs + " ms | nós="
                + exec.path("metrics").path("nodeCount").asInt()
                + " | sem delta (declarado) | SNS órfão (sem consumidor)");

        Path dir = Path.of("..", "..", "docs", "qa").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("evidence-station-companion.json"), exec.toPrettyString());
        System.out.println("[E2E-STATION] evidência: docs/qa/evidence-station-companion.json");
    }

    private JsonNode awaitStationExecution(String orderId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String stationBase = stationUrl() + "/tracevanta";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create(stationBase + "/api/executions?limit=20"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode list = MAPPER.readTree(response.body());
            for (JsonNode summary : list) {
                if ("RUNNING".equals(summary.path("status").asText())) {
                    continue;
                }
                HttpResponse<String> full = HTTP.send(HttpRequest.newBuilder()
                                .uri(URI.create(stationBase + "/api/executions/"
                                        + summary.path("executionId").asText()))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode execution = MAPPER.readTree(full.body());
                // a execução é deste disparo se a árvore contém o POST /orders (modo Companion:
                // sem delta, identificamos pelo rótulo do span raiz HTTP)
                if (execution.path("roots").size() > 0 && hasLabel(execution, "/orders")) {
                    return execution;
                }
            }
            Thread.sleep(300);
        }
        throw new AssertionError("execução do Station não apareceu em " + timeout);
    }

    private static boolean hasLabel(JsonNode exec, String labelPart) {
        List<JsonNode> nodes = new ArrayList<>();
        collect(exec.path("roots"), nodes);
        return nodes.stream().anyMatch(n -> n.path("label").asText().contains(labelPart));
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
