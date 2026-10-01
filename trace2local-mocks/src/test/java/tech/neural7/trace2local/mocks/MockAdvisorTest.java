package tech.neural7.trace2local.mocks;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.advisor.ContractCatalog;
import tech.neural7.trace2local.mocks.advisor.MockAdvisor;
import tech.neural7.trace2local.mocks.advisor.MockSuggestion;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;
import tech.neural7.trace2local.model.ErrorInfo;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionMetrics;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;
import tech.neural7.trace2local.model.Payload;
import tech.neural7.trace2local.model.Trigger;
import tech.neural7.trace2local.otel.OtelAttributeNames;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** O conselheiro sugere QUANDO plugar mock e QUAIS variações — com cenário e controle. */
class MockAdvisorTest {

    @TempDir
    Path dir;
    private final List<Execution> executions = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private MockConnectWorker worker;
    private MockAdvisor advisor;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(dir.resolve("contracts"));
        Files.writeString(dir.resolve("contracts/antifraude.yaml"), OpenApiAndWireMockTest.SPEC);
        worker = new MockConnectWorker(MockConnectWorker.Settings.loopback(0).withDataDir(dir), () -> List.of());
        worker.start();
        advisor = new MockAdvisor(() -> List.copyOf(executions), worker, new ContractCatalog(dir));
    }

    @AfterEach
    void tearDown() {
        worker.close();
    }

    // ---------------------------------------------------------------- fábrica de execuções

    private Node client(String peer, String host, int port, String method, String route, int status, String response,
                        ErrorInfo error, long ms) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put(OtelAttributeNames.HTTP_METHOD, method);
        a.put(OtelAttributeNames.SERVER_ADDRESS, host);
        a.put(OtelAttributeNames.SERVER_PORT, String.valueOf(port));
        a.put(OtelAttributeNames.URL_FULL, "http://" + host + ":" + port + route);
        a.put(OtelAttributeNames.PEER_SERVICE, peer);
        if (status > 0) {
            a.put(OtelAttributeNames.HTTP_STATUS, String.valueOf(status));
        }
        return new Node("n" + seq.incrementAndGet(), null, NodeKind.HTTP_CLIENT, method + " " + peer + " " + route,
                error != null || status >= 400 ? NodeStatus.ERROR : NodeStatus.OK, Instant.now(), Duration.ofMillis(ms),
                Duration.ofMillis(ms), a, response == null ? null : new Payload("{}", response), null, error, List.of());
    }

    private Node step(NodeKind kind, String label) {
        return new Node("n" + seq.incrementAndGet(), null, kind, label, NodeStatus.OK, Instant.now(), Duration.ofMillis(2),
                Duration.ofMillis(2), Map.of(), null, null, null, List.of());
    }

    private Execution exec(ExecutionStatus status, String httpStatus, long ms, Node... children) {
        Node root = new Node("r" + seq.incrementAndGet(), null, NodeKind.HTTP_SERVER, "POST /pix", NodeStatus.OK, Instant.now(),
                Duration.ofMillis(1), Duration.ofMillis(ms), Map.of(OtelAttributeNames.HTTP_STATUS, httpStatus), null, null, null,
                List.of(children));
        Execution e = new Execution("exec-" + seq.incrementAndGet(), "t" + seq.get(), status, Trigger.EXTERNAL, Instant.now(),
                Duration.ofMillis(ms), List.of(root), ExecutionMetrics.EMPTY, List.of());
        executions.add(0, e); // mais recentes primeiro (como o store)
        return e;
    }

    private Node antifraude(String decision, int score) {
        return client("Antifraude", "antifraude.parceiro", 8080, "POST", "/api/v1/score", 200,
                "{\"decision\":\"" + decision + "\",\"score\":" + score + ",\"requestId\":\"" + java.util.UUID.randomUUID() + "\"}",
                null, 40);
    }

    // ---------------------------------------------------------------- regras

    @Test
    void unreachableDependencySuggestsPluggingAMockFromTheContract() {
        exec(ExecutionStatus.FAILED, "502", 30, client("Antifraude", "antifraude.parceiro", 8080, "POST", "/api/v1/score", -1,
                null, new ErrorInfo("java.net.ConnectException", "Connection refused", null), 3));

        MockSuggestion s = advisor.suggest().stream().filter(x -> x.kind().equals("UNAVAILABLE_DEPENDENCY")).findFirst().orElseThrow();
        assertThat(s.severity()).isEqualTo("HIGH");
        assertThat(s.title()).contains("Antifraude");
        assertThat(s.bindingConfig()).containsEntry("source", "openapi").containsEntry("source.spec", "contracts/antifraude.yaml")
                .containsEntry("target", "antifraude.parceiro:8080");
        assertThat(s.evidence()).singleElement().satisfies(e -> assertThat(e.text()).contains("ConnectException"));

        // aplicar → mock de pé e a próxima chamada responde pelo contrato
        var info = advisor.apply(s, List.of(), "exclusive", Map.of());
        assertThat(info.status().state()).isEqualTo("RUNNING");
        assertThat(info.config()).doesNotContainKey("transforms"); // sem variação pedida = resposta base do contrato
        assertThat(worker.routes()).singleElement().satisfies(r -> assertThat(r.target()).isEqualTo("antifraude.parceiro:8080"));
        assertThat(advisor.suggest()).noneMatch(x -> x.kind().equals("UNAVAILABLE_DEPENDENCY") && x.activeBinding() == null);
    }

    @Test
    void responseFieldThatDrivesTheFlowBecomesVariationsIncludingContractValuesNeverSeen() throws Exception {
        exec(ExecutionStatus.COMPLETED, "201", 120, antifraude("APPROVED", 120), step(NodeKind.SQL, "SQL: INSERT ledger"),
                step(NodeKind.SQS, "SQS: pix-settlement"));
        exec(ExecutionStatus.COMPLETED, "202", 90, antifraude("REVIEW", 640), step(NodeKind.SNS, "SNS: pix-review"));
        exec(ExecutionStatus.COMPLETED, "201", 110, antifraude("APPROVED", 150), step(NodeKind.SQL, "SQL: INSERT ledger"),
                step(NodeKind.SQS, "SQS: pix-settlement"));

        MockSuggestion s = advisor.suggest().stream().filter(x -> x.kind().equals("RESPONSE_DRIVES_FLOW")).findFirst().orElseThrow();
        assertThat(s.title()).contains("/decision");                    // categórico vence o numérico (score)
        assertThat(s.why()).contains("APPROVED").contains("REVIEW").contains("pix-review");
        assertThat(s.variations()).extracting(MockSuggestion.Variation::title)
                .contains("/decision = DENIED", "/decision = null", "/decision ausente");
        assertThat(s.variations()).filteredOn(v -> v.title().equals("/decision = DENIED")).singleElement()
                .satisfies(v -> assertThat(v.category()).isEqualTo("CONTRACT"));
        assertThat(s.evidence()).hasSize(2);

        // sob demanda: a variação só vale quando a requisição traz baggage t2l.mock=<id>
        String denied = s.variations().stream().filter(v -> v.title().equals("/decision = DENIED")).findFirst().orElseThrow().id();
        Files.writeString(dir.resolve("contracts/antifraude.yaml"), OpenApiAndWireMockTest.SPEC);
        var info = advisor.apply(s, List.of(denied), "on-demand",
                Map.of("source", "openapi", "source.spec", "contracts/antifraude.yaml", "unmatched", "not-found"));
        assertThat(info.status().state()).as(String.valueOf(info.status().trace())).isEqualTo("RUNNING");
        HttpClient http = HttpClient.newHttpClient();
        String url = info.status().endpoint() + "/api/v1/score";
        JsonNode normal = JsonSupport.parse(http.send(HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).body());
        JsonNode chosen = JsonSupport.parse(http.send(HttpRequest.newBuilder(URI.create(url))
                .header("baggage", "outro=1, " + MockAdvisor.BAGGAGE_KEY + "=" + denied)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).body());
        assertThat(normal.path("decision").asText()).isEqualTo("APPROVED");
        assertThat(chosen.path("decision").asText()).isEqualTo("DENIED");

        // reaplicar troca as variações do conselheiro, sem acumular
        var again = advisor.apply(s, List.of("decision-review"), "exclusive", Map.of());
        assertThat(again.config().get("transforms")).isEqualTo("v-decision-review");
        assertThat(again.config().get("predicates")).isEqualTo("p-decision-review"); // restrita à operação
        assertThat(again.config()).doesNotContainKey("transforms.v-decision-denied.type");
    }

    @Test
    void threeExecutionsWithThreeDistinctDecisionsAreStillACategoricalDriver() {
        exec(ExecutionStatus.COMPLETED, "202", 120, antifraude("APPROVED", 112), step(NodeKind.SQS, "SQS: pix-settlement"));
        exec(ExecutionStatus.COMPLETED, "202", 90, antifraude("REVIEW", 640), step(NodeKind.SNS, "SNS: pix-review"));
        exec(ExecutionStatus.COMPLETED, "422", 80, antifraude("DENIED", 930), step(NodeKind.SQL, "SQL: UPDATE ledger_entries"));
        MockSuggestion s = advisor.suggest().stream().filter(x -> x.kind().equals("RESPONSE_DRIVES_FLOW")).findFirst().orElseThrow();
        assertThat(s.title()).contains("/decision");
    }

    @Test
    void controlSamePathForAllValuesDoesNotClaimTheFieldDrivesTheFlow() {
        exec(ExecutionStatus.COMPLETED, "201", 100, antifraude("APPROVED", 120), step(NodeKind.SQS, "SQS: pix-settlement"));
        exec(ExecutionStatus.COMPLETED, "201", 100, antifraude("REVIEW", 640), step(NodeKind.SQS, "SQS: pix-settlement"));
        assertThat(advisor.suggest()).noneMatch(s -> s.kind().equals("RESPONSE_DRIVES_FLOW"));
        // ... mas aponta que só sucesso foi exercitado
        MockSuggestion happy = advisor.suggest().stream().filter(s -> s.kind().equals("HAPPY_PATH_ONLY")).findFirst().orElseThrow();
        assertThat(happy.variations()).extracting(MockSuggestion.Variation::id)
                .contains("http-503", "timeout", "falha-transitoria", "decision-ausente", "campo-extra");
    }

    @Test
    void slowDependencyAndContractDrift() {
        Node slowCall = client("KYC", "kyc.bureau", 8443, "GET", "/v2/kyc/{id}", 200, "{\"status\":\"VERIFIED\"}", null, 1800);
        exec(ExecutionStatus.COMPLETED, "200", 2000, slowCall);
        exec(ExecutionStatus.COMPLETED, "200", 2100,
                client("KYC", "kyc.bureau", 8443, "GET", "/v2/kyc/{id}", 200, "{\"status\":\"VERIFIED\"}", null, 1900));
        exec(ExecutionStatus.COMPLETED, "201", 100, client("Antifraude", "antifraude.parceiro", 8080, "POST", "/api/v1/score", 200,
                "{\"decision\":\"MAYBE\"}", null, 10));

        List<MockSuggestion> all = advisor.suggest();
        assertThat(all).anySatisfy(s -> {
            assertThat(s.kind()).isEqualTo("SLOW_DEPENDENCY");
            assertThat(s.api()).isEqualTo("KYC");
        });
        MockSuggestion drift = all.stream().filter(s -> s.kind().equals("CONTRACT_DRIFT")).findFirst().orElseThrow();
        assertThat(drift.why()).contains("/score").contains("MAYBE");
    }

    @Test
    void responsesServedByMocksAreNeverReplayedAsReal() {
        Node mocked = client("Antifraude", "antifraude.parceiro", 8080, "POST", "/api/v1/score", 200, "{\"decision\":\"DENIED\"}", null, 2);
        Map<String, String> attrs = new LinkedHashMap<>(mocked.attributes());
        attrs.put(tech.neural7.trace2local.otel.Trace2LocalAttributes.MOCK, "binding=x; stub=y");
        exec(ExecutionStatus.COMPLETED, "201", 10, new Node(mocked.nodeId(), null, mocked.kind(), mocked.label(), mocked.status(),
                mocked.startedAt(), mocked.selfTime(), mocked.totalTime(), attrs, mocked.payload(), null, null, List.of()));
        assertThat(advisor.suggest()).noneMatch(s -> s.kind().equals("HAPPY_PATH_ONLY") || s.kind().equals("CONTRACT_DRIFT"));
    }
}
