package tech.neural7.trace2local.mocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.json.WireMockFormat;
import tech.neural7.trace2local.mocks.model.Fault;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.openapi.OpenApiDocument;
import tech.neural7.trace2local.mocks.plugins.source.OpenApiStubSource;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contrato OpenAPI → stubs; interoperabilidade com o formato do WireMock; destino WireMock (admin API). */
class OpenApiAndWireMockTest {

    static final String SPEC = """
            openapi: 3.0.3
            info: {title: Antifraude Parceiro, version: "1.4"}
            servers:
              - url: http://antifraude.parceiro:8080/api
            paths:
              /v1/score:
                post:
                  operationId: avaliarRisco
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: {$ref: '#/components/schemas/Score'}
                          examples:
                            aprovado: {value: {decision: APPROVED, score: 120, reasons: []}}
                            revisao: {value: {decision: REVIEW, score: 640, reasons: [VALOR_ATIPICO]}}
                    "422":
                      description: inválido
                      content:
                        application/json:
                          example: {code: INVALID_PAYLOAD}
              /v1/clients/{cpf}/limits:
                get:
                  operationId: consultarLimite
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema:
                            allOf:
                              - type: object
                                required: [daily]
                                properties:
                                  daily: {type: number, minimum: 0}
                              - type: object
                                properties:
                                  updatedAt: {type: string, format: date-time}
            components:
              schemas:
                Score:
                  type: object
                  required: [decision, score]
                  properties:
                    decision: {type: string, enum: [APPROVED, REVIEW, DENIED]}
                    score: {type: integer}
                    reasons: {type: array, items: {type: string}}
            """;

    @TempDir
    Path dir;

    @Test
    void openApiProducesStubsFromNamedExamplesAndSchemas() throws Exception {
        OpenApiDocument doc = OpenApiDocument.parse(SPEC, "antifraude.yaml");
        assertThat(doc.hosts()).contains("antifraude.parceiro:8080", "antifraude.parceiro");
        assertThat(doc.basePath()).isEqualTo("/api");

        List<Stub> stubs = OpenApiStubSource.stubs(doc, "2xx", "revisao", List.of(), true, "antifraude.yaml");
        Stub score = stubs.stream().filter(s -> s.id().equals("avaliarRisco")).findFirst().orElseThrow();
        assertThat(score.request().path()).isEqualTo("/api/v1/score");
        assertThat(score.response().bodyJson().path("decision").asText()).isEqualTo("REVIEW");

        Stub limite = stubs.stream().filter(s -> s.id().equals("consultarLimite")).findFirst().orElseThrow();
        assertThat(limite.response().bodyJson().has("daily")).isTrue();            // allOf mesclado
        assertThat(limite.notes()).anyMatch(n -> n.contains("gerado a partir do schema"));

        var op = doc.operations().stream().filter(o -> "avaliarRisco".equals(o.operationId())).findFirst().orElseThrow();
        assertThat(doc.enums(op, "200")).containsEntry("/decision", List.of("APPROVED", "REVIEW", "DENIED"));
        assertThat(doc.requiredFields(op, "200")).containsExactly("/decision", "/score");
        assertThat(OpenApiStubSource.stubs(doc, "422", null, List.of("avaliarRisco"), true, "x").get(0).response().status())
                .isEqualTo(422);
    }

    @Test
    void wireMockMappingsRoundTripWithConstraintsAndFaults() {
        JsonNode mapping = JsonSupport.parse("""
                {"name":"dict ok","priority":3,
                 "request":{"method":"GET","urlPathTemplate":"/api/v2/entries/{key}",
                   "queryParameters":{"ispb":{"equalTo":"60701190"}},
                   "headers":{"Authorization":{"matches":"Bearer .+"}},
                   "bodyPatterns":[{"matchesJsonPath":{"expression":"$.account.type","equalTo":"CACC"}}]},
                 "response":{"status":200,"jsonBody":{"ok":true},"fixedDelayMilliseconds":50,"fault":"CONNECTION_RESET_BY_PEER"}}""");
        Stub s = WireMockFormat.read(mapping, "test").get(0);
        assertThat(s.priority()).isEqualTo(3);
        assertThat(s.request().query()).containsEntry("ispb", "60701190");
        assertThat(s.request().constraints()).hasSize(2);
        assertThat(s.request().constraints().get(1).key()).isEqualTo("/account/type");
        assertThat(s.response().fault()).isEqualTo(Fault.CONNECTION_RESET);

        JsonNode back = WireMockFormat.write(s, "dict", 0);
        assertThat(back.at("/request/urlPathTemplate").asText()).isEqualTo("/api/v2/entries/{key}");
        assertThat(back.at("/request/bodyPatterns/0/matchesJsonPath/expression").asText()).isEqualTo("$.account.type");
        assertThat(back.at("/response/fault").asText()).isEqualTo("CONNECTION_RESET_BY_PEER");
        assertThat(back.at("/metadata/trace2local/binding").asText()).isEqualTo("dict");
    }

    @Test
    void wireMockSinkPublishesBakedVariationsAndRemovesOnlyItsOwnMappings() throws Exception {
        List<JsonNode> posted = new ArrayList<>();
        List<JsonNode> removals = new ArrayList<>();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/__admin/mappings", ex -> {
            JsonNode body = JsonSupport.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (ex.getRequestURI().getPath().endsWith("remove-by-metadata")) {
                removals.add(body);
                ex.sendResponseHeaders(200, -1);
            } else {
                posted.add(body);
                ex.sendResponseHeaders(201, -1);
            }
            ex.close();
        });
        fake.start();
        Files.writeString(dir.resolve("antifraude.yaml"), SPEC);
        MockConnectWorker worker = new MockConnectWorker(MockConnectWorker.Settings.loopback(0).withDataDir(dir), List::of);
        worker.start();
        try {
            Map<String, String> cfg = new LinkedHashMap<>();
            cfg.put("target", "antifraude.parceiro:8080");
            cfg.put("source", "openapi");
            cfg.put("source.spec", "antifraude.yaml");
            cfg.put("sink", "wiremock");
            cfg.put("sink.url", "http://127.0.0.1:" + fake.getAddress().getPort());
            cfg.put("transforms", "negado");
            cfg.put("transforms.negado.type", "set-field");
            cfg.put("transforms.negado.pointer", "/decision");
            cfg.put("transforms.negado.value", "DENIED");
            cfg.put("transforms.negado.predicate", "cenario");
            cfg.put("predicates", "cenario");
            cfg.put("predicates.cenario.type", "header-matches");
            cfg.put("predicates.cenario.name", "X-Cenario");
            cfg.put("predicates.cenario.regex", "negado");
            var info = worker.create("antifraude", cfg);
            assertThat(info.status().state()).as(String.valueOf(info.status().trace())).isEqualTo("RUNNING");

            // 2 operações base + 2 variações por cabeçalho (stub adicional, prioridade maior)
            assertThat(posted).hasSize(4);
            JsonNode variation = posted.stream().filter(p -> p.at("/request/headers/X-Cenario").isObject()
                    && p.at("/request/urlPath").asText().equals("/api/v1/score")).findFirst().orElseThrow();
            assertThat(variation.at("/response/jsonBody/decision").asText()).isEqualTo("DENIED");
            assertThat(variation.path("priority").asInt()).isEqualTo(1);
            assertThat(removals).hasSize(1); // limpa o que publicou antes (idempotente)
            assertThat(removals.get(0).at("/matchesJsonPath/expression").asText()).isEqualTo("$.trace2local.binding");

            worker.delete("antifraude");
            assertThat(removals).hasSize(2);
        } finally {
            worker.close();
            fake.stop(0);
        }
    }

    @Test
    void fileSinkExportsVersionableMappings() throws Exception {
        MockConnectWorker worker = new MockConnectWorker(MockConnectWorker.Settings.loopback(0).withDataDir(dir), List::of);
        worker.start();
        try {
            Map<String, String> cfg = new LinkedHashMap<>();
            cfg.put("target", "kyc:8080");
            cfg.put("source", "inline");
            cfg.put("source.stubs", "[{\"method\":\"GET\",\"path\":\"/kyc/{doc}\",\"body\":{\"status\":\"VERIFIED\"}}]");
            cfg.put("sink", "file");
            cfg.put("transforms", "pendente");
            cfg.put("transforms.pendente.type", "set-field");
            cfg.put("transforms.pendente.pointer", "/status");
            cfg.put("transforms.pendente.value", "PENDING");
            var info = worker.create("kyc", cfg);
            assertThat(info.status().state()).isEqualTo("RUNNING");
            Path exported = dir.resolve("exports/kyc/mappings");
            try (var files = Files.list(exported)) {
                Path f = files.findFirst().orElseThrow();
                assertThat(JsonSupport.parse(Files.readString(f)).at("/response/jsonBody/status").asText()).isEqualTo("PENDING");
            }
        } finally {
            worker.close();
        }
        assertThat(Files.list(dir.resolve("exports/kyc/mappings")).count()).as("exportação sobrevive ao shutdown").isEqualTo(1);
    }
}
