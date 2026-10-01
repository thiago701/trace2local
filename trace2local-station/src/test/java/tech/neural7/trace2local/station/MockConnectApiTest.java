package tech.neural7.trace2local.station;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.mocks.advisor.ContractCatalog;
import tech.neural7.trace2local.mocks.advisor.MockAdvisor;
import tech.neural7.trace2local.mocks.rest.MockConnectRestHandler;
import tech.neural7.trace2local.mocks.rest.MockRoutesIngestHandler;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;
import tech.neural7.trace2local.server.Trace2LocalHttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** API do Mock Connect montada no servidor do Station: formato Connect + RequestGuard + canal de rotas. */
class MockConnectApiTest {

    private static final String BINDING = """
            {"name":"mock-kyc","config":{
              "target":"kyc.bureau:8443","api.name":"KYC",
              "source":"inline","source.stubs":"[{\\"method\\":\\"GET\\",\\"path\\":\\"/v2/kyc/{doc}\\",\\"body\\":{\\"status\\":\\"VERIFIED\\"}}]",
              "transforms":"pendente","transforms.pendente.type":"set-field",
              "transforms.pendente.pointer":"/status","transforms.pendente.value":"PENDING"}}""";

    @Test
    void connectStyleRestApiBehindTheRequestGuard() throws Exception {
        Trace2LocalConfig cfg = Trace2LocalConfig.builder().port(0).stationToken("tk").build();
        try (Trace2LocalPipeline pipeline = Trace2LocalPipeline.start(cfg);
             MockConnectWorker worker = new MockConnectWorker(MockConnectWorker.Settings.loopback(0), List::of)) {
            worker.start();
            ContractCatalog catalog = new ContractCatalog(null);
            Trace2LocalHttpServer server = Trace2LocalHttpServer.builder(cfg, pipeline)
                    .apiRoute("mocks", new MockConnectRestHandler(worker, new MockAdvisor(List::of, worker, catalog), catalog))
                    .extraRoute("/t2lingest/v1/mock-routes", new MockRoutesIngestHandler(worker))
                    .build();
            server.start();
            try {
                HttpClient http = HttpClient.newHttpClient();
                String root = "http://127.0.0.1:" + server.port();
                String base = root + cfg.basePath();

                // mutação sem prova de mesma origem: recusada pelo RequestGuard
                HttpResponse<String> forged = http.send(HttpRequest.newBuilder(URI.create(base + "/api/mocks/bindings"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(BINDING)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(forged.statusCode()).isEqualTo(403);

                HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create(base + "/api/mocks/bindings"))
                        .header("Content-Type", "application/json").header("X-Trace2Local", "1")
                        .POST(HttpRequest.BodyPublishers.ofString(BINDING)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
                assertThat(created.headers().firstValue("Content-Security-Policy")).isPresent();
                assertThat(JsonSupport.parse(created.body()).at("/status/state").asText()).isEqualTo("RUNNING");

                HttpResponse<String> dup = http.send(HttpRequest.newBuilder(URI.create(base + "/api/mocks/bindings"))
                        .header("Content-Type", "application/json").header("X-Trace2Local", "1")
                        .POST(HttpRequest.BodyPublishers.ofString(BINDING)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(dup.statusCode()).isEqualTo(409);

                JsonNode plugins = get(http, base + "/api/mocks/plugins");
                assertThat(plugins.findValuesAsText("name")).contains("openapi", "observed", "inline", "embedded", "wiremock",
                        "file", "set-field", "fault", "template", "call-count", "header-matches");

                HttpResponse<String> validate = http.send(HttpRequest.newBuilder(URI.create(base + "/api/mocks/plugins/latency/config/validate"))
                        .header("Content-Type", "application/json").header("X-Trace2Local", "1")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"ms\":\"999999\"}")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(JsonSupport.parse(validate.body()).path("errorCount").asInt()).isEqualTo(1);

                assertThat(get(http, base + "/api/mocks/bindings/mock-kyc/status").path("state").asText()).isEqualTo("RUNNING");
                assertThat(get(http, base + "/api/mocks/bindings/mock-kyc/export").at("/mappings/0/response/jsonBody/status").asText())
                        .isEqualTo("PENDING");

                // canal de ingest: exige o Bearer do Station (consumido por containers)
                HttpResponse<String> noToken = http.send(HttpRequest.newBuilder(URI.create(root + "/t2lingest/v1/mock-routes")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(noToken.statusCode()).isEqualTo(401);
                HttpResponse<String> routes = http.send(HttpRequest.newBuilder(URI.create(root + "/t2lingest/v1/mock-routes"))
                        .header("Authorization", "Bearer tk").build(), HttpResponse.BodyHandlers.ofString());
                assertThat(JsonSupport.parse(routes.body()).get(0).path("target").asText()).isEqualTo("kyc.bureau:8443");

                HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(URI.create(base + "/api/mocks/bindings/mock-kyc"))
                        .header("X-Trace2Local", "1").DELETE().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(deleted.statusCode()).isEqualTo(200);
                assertThat(get(http, base + "/api/mocks/bindings").size()).isZero();
            } finally {
                server.close();
            }
        }
    }

    private static JsonNode get(HttpClient http, String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(url + " → " + r.body()).isEqualTo(200);
        return JsonSupport.parse(r.body());
    }
}
