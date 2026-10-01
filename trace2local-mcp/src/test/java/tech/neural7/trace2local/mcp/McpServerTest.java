package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato do servidor MCP: negociação, catálogo (somente leitura por padrão), ferramentas
 * sobre a API real do Trace2Local (aqui, uma imitação fiel), política de dados, gating de
 * mutações e os dois transportes.
 */
class McpServerTest {

    private FakeTrace2Local fake;
    private int ids;

    @BeforeEach
    void up() throws Exception {
        fake = new FakeTrace2Local();
    }

    @AfterEach
    void down() {
        fake.close();
    }

    private McpServer server(boolean mutations, McpConfig.DataMode mode) {
        return new McpServer(new McpConfig(fake.base(), null, mutations, mode, false, 0, null, Duration.ofSeconds(5), 60_000));
    }

    private JsonNode rpc(McpServer s, String method, JsonNode params) {
        ObjectNode m = Json.obj().put("jsonrpc", "2.0").put("id", ++ids).put("method", method);
        if (params != null) {
            m.set("params", params);
        }
        return s.handle(m);
    }

    private JsonNode call(McpServer s, String tool, ObjectNode args) {
        ObjectNode p = Json.obj().put("name", tool);
        p.set("arguments", args == null ? Json.obj() : args);
        return rpc(s, "tools/call", p).path("result");
    }

    private static String text(JsonNode result) {
        return result.path("content").path(0).path("text").asText();
    }

    @Test
    void negotiatesTheProtocolAndExplainsHowToUseTheTools() {
        McpServer s = server(false, null);
        JsonNode r = rpc(s, "initialize", Json.obj().put("protocolVersion", "2025-06-18")
                .set("clientInfo", Json.obj().put("name", "teste").put("version", "1"))).path("result");
        assertThat(r.path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(r.path("serverInfo").path("name").asText()).isEqualTo("trace2local");
        assertThat(r.path("capabilities").has("tools")).isTrue();
        assertThat(r.path("instructions").asText()).contains("diagnose_failure").contains("mutações desligadas");
        // versão desconhecida → a mais nova suportada
        JsonNode r2 = rpc(s, "initialize", Json.obj().put("protocolVersion", "1999-01-01")).path("result");
        assertThat(r2.path("protocolVersion").asText()).isEqualTo(McpServer.PROTOCOL_VERSIONS.get(0));
        assertThat(s.handle(Json.obj().put("jsonrpc", "2.0").put("method", "notifications/initialized"))).isNull();
        assertThat(rpc(s, "ping", null).path("result").isObject()).isTrue();
        assertThat(rpc(s, "sampling/createMessage", null).path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void readOnlyByDefaultMutationsOnlyWithOptIn() {
        List<String> readOnly = names(rpc(server(false, null), "tools/list", null));
        assertThat(readOnly).contains("status", "list_executions", "get_execution", "get_step", "diagnose_failure",
                        "explain_execution", "list_mock_suggestions", "validate_mock_binding")
                .doesNotContain("dispatch_endpoint", "apply_mock_suggestion", "put_mock_binding", "control_mock_binding");
        List<String> all = names(rpc(server(true, null), "tools/list", null));
        assertThat(all).contains("dispatch_endpoint", "apply_mock_suggestion", "put_mock_binding", "control_mock_binding");

        JsonNode def = rpc(server(true, null), "tools/list", null).path("result").path("tools");
        for (JsonNode t : def) {
            assertThat(t.path("inputSchema").path("type").asText()).isEqualTo("object");
            assertThat(t.path("description").asText().length()).isGreaterThan(40);
            boolean mutating = List.of("dispatch_endpoint", "apply_mock_suggestion", "put_mock_binding", "control_mock_binding")
                    .contains(t.path("name").asText());
            assertThat(t.path("annotations").path("readOnlyHint").asBoolean()).isEqualTo(!mutating);
            assertThat(t.path("annotations").path("openWorldHint").asBoolean()).isFalse();
        }
        // bloqueada: explica como ligar (isError, não erro de protocolo); desconhecida: -32602
        JsonNode blocked = call(server(false, null), "dispatch_endpoint", Json.obj().put("endpointId", "x"));
        assertThat(blocked.path("isError").asBoolean()).isTrue();
        assertThat(text(blocked)).contains("TRACE2LOCAL_MCP_ALLOW_MUTATIONS=true");
        assertThat(fake.calls).noneMatch(c -> c.path().startsWith("/execute"));
        JsonNode unknown = rpc(server(false, null), "tools/call", Json.obj().put("name", "rm_rf"));
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void coreToolProfileTrimsTheResidentSchema() {
        McpConfig core = McpConfig.from(new String[]{"--url", fake.base().toString(), "--tools", "core"}, Map.of());
        List<String> names = names(rpc(new McpServer(core), "tools/list", null));
        assertThat(names).containsExactlyInAnyOrderElementsOf(McpConfig.CORE_TOOLS);
        JsonNode outside = rpc(new McpServer(core), "tools/call", Json.obj().put("name", "get_topology"));
        assertThat(outside.path("error").path("code").asInt()).isEqualTo(-32602);
        McpConfig coreMut = McpConfig.from(new String[]{"--url", fake.base().toString(), "--tools", "core", "--allow-mutations"}, Map.of());
        assertThat(names(rpc(new McpServer(coreMut), "tools/list", null))).contains("dispatch_endpoint", "apply_mock_suggestion");
        McpConfig list = McpConfig.from(new String[]{"--url", fake.base().toString(), "--tools", "status, get_step"}, Map.of());
        assertThat(names(rpc(new McpServer(list), "tools/list", null))).containsExactlyInAnyOrder("status", "get_step");
    }

    @Test
    void outlineShowsIdsErrorsDataMocksAndQueueWait() {
        McpServer s = server(false, null);
        String fail = text(call(s, "get_execution", Json.obj().put("executionId", "ex-fail")));
        assertThat(fail).contains("[n-spi] HTTP_CLIENT POST SPI (BACEN)")
                .contains("✕ PartnerUnavailableException: SPI (BACEN) indisponível: HTTP 503")
                .contains("Δ CREATE pix-transfers chave=pix-1 campos=status")
                .contains("⧗ fila 1.10 s");
        String ok = text(call(s, "get_execution", Json.obj().put("executionId", "ex-ok")));
        assertThat(ok).contains("[SIM binding=mock-kyc-limites; stub=consultarLimites]");
        JsonNode missing = call(s, "get_execution", Json.obj().put("executionId", "nao-existe"));
        assertThat(missing.path("isError").asBoolean()).isTrue();
        assertThat(text(missing)).contains("não está no acervo").contains("list_executions");
    }

    @Test
    void listFiltersByStatusAndQuery() {
        McpServer s = server(false, null);
        JsonNode r = call(s, "list_executions", Json.obj().put("status", "FAILED"));
        assertThat(text(r)).contains("ex-fail").doesNotContain("ex-ok");
        assertThat(r.path("structuredContent").path("executions").size()).isEqualTo(1);
        assertThat(text(call(s, "list_executions", Json.obj().put("query", "BBBB")))).contains("ex-ok");
    }

    @Test
    void structuralDataPolicyOmitsPayloadBodiesFullIncludesThem() {
        String structural = text(call(server(false, McpConfig.DataMode.STRUCTURAL), "get_step",
                Json.obj().put("executionId", "ex-fail").put("nodeId", "n-spi")));
        assertThat(structural).contains("Caminho: pix-api · POST /pix/transfers › Aceitar e enviar")
                .contains("ERRO PartnerUnavailableException")
                .contains("SpiClient.java:42")
                .contains("request: [omitido")
                .contains("t2l.payload.request = [omitido")
                .doesNotContain("E123")
                .contains("SPI respondeu 503"); // log preso ao passo
        String full = text(call(server(false, McpConfig.DataMode.FULL), "get_step",
                Json.obj().put("executionId", "ex-fail").put("nodeId", "n-spi")));
        assertThat(full).contains("\"endToEndId\":\"E123\"");
        // passo de entrada (Lambda) recebe também as linhas de plataforma do seu RequestId
        String lambda = text(call(server(false, null), "get_step", Json.obj().put("executionId", "ex-fail").put("nodeId", "n-settle")));
        assertThat(lambda).contains("START RequestId: r-2").contains("liquidação falhou");
    }

    @Test
    void diagnoseFindsTheDeepestErrorLogsVerdictInsightAndTheReadyMock() {
        JsonNode r = call(server(false, null), "diagnose_failure", null);
        String t = text(r);
        assertThat(r.path("isError").asBoolean()).isFalse();
        assertThat(t).contains("Execução ex-fail")
                .contains("CAUSA(S) RAIZ — FATO OBSERVADO")
                .contains("[n-spi] HTTP_CLIENT POST SPI (BACEN)")
                .contains("caminho: pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement")
                .contains("(+2 passo(s) ancestral(is) marcados com erro por propagação)")
                .contains("ERROR")
                .contains("desfecho: Falha técnica (93%")
                .contains("insight RES-001")
                .contains("MOCK CONNECT — Só o caminho feliz de SPI (BACEN)")
                .contains("get_step(ex-fail, n-spi)");
    }

    @Test
    void explainAndCompareSpeakBusinessAndDiff() {
        McpServer s = server(false, null);
        String exec = text(call(s, "explain_execution", Json.obj().put("executionId", "ex-fail").put("audience", "executive")));
        assertThat(exec).contains("R1 VIOLADA · pix-settlement").contains("[ ] Fluxo concluiu").doesNotContain("TÉCNICO");
        String diff = text(call(s, "compare_executions", Json.obj().put("a", "ex-ok").put("b", "ex-fail")));
        assertThat(diff).contains("SÓ EM B").contains("Liquidar no SPI").contains("MUDOU")
                .contains("status OK → ERROR (PartnerUnavailableException");
    }

    @Test
    void mockSuggestionsAndApplyWithMutations() {
        String sug = text(call(server(false, null), "list_mock_suggestions", Json.obj().put("host", "spi.bacen.local")));
        assertThat(sug).contains("[happy-spi]").contains("http-503").contains("falha-transitoria")
                .contains("predicado próprio");
        JsonNode applied = call(server(true, null), "apply_mock_suggestion", Json.obj().put("suggestionId", "happy-spi")
                .put("mode", "on-demand").set("variations", Json.arr().add("http-503")));
        assertThat(text(applied)).contains("mock-spi: RUNNING").contains("baggage: t2l.mock=http-503");
        FakeTrace2Local.Call post = fake.calls.stream().filter(c -> c.path().endsWith("/apply")).findFirst().orElseThrow();
        assertThat(post.guardHeader()).isEqualTo("1"); // anti-CSRF do Trace2Local respeitado
        assertThat(post.body()).contains("\"mode\":\"on-demand\"").contains("http-503");
    }

    @Test
    void dispatchFollowsTheTraceUntilTheExecutionArrives() {
        JsonNode r = call(server(true, null), "dispatch_endpoint", Json.obj().put("endpointId", "getTransfer")
                .set("pathVariables", Json.obj().put("transferId", "pix-1")));
        assertThat(text(r)).contains("trace cccccccccccccccccccccccccccccccc").contains("[x-root] LAMBDA pix-api · GET");
        FakeTrace2Local.Call post = fake.calls.stream().filter(c -> c.path().equals("/execute")).findFirst().orElseThrow();
        assertThat(post.body()).contains("\"transferId\":\"pix-1\"");
    }

    @Test
    void outputIsTruncatedForTheModel() {
        McpServer s = new McpServer(new McpConfig(fake.base(), null, false, null, false, 0, null, Duration.ofSeconds(5), 200));
        String t = text(call(s, "get_execution", Json.obj().put("executionId", "ex-fail")));
        assertThat(t).contains("saída truncada em 200 caracteres");
    }

    @Test
    void unreachableTrace2LocalIsAnActionableToolError() {
        McpServer s = new McpServer(new McpConfig(URI.create("http://127.0.0.1:1/trace2local"), null, false, null, false, 0, null,
                Duration.ofSeconds(2), 60_000));
        JsonNode r = call(s, "status", null);
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("inacessível").contains("TRACE2LOCAL_URL");
    }

    @Test
    void configIsLocalFirst() {
        assertThatThrownBy(() -> McpConfig.from(new String[]{"--url", "http://observabilidade.empresa.com/trace2local"}, Map.of()))
                .hasMessageContaining("fora do loopback");
        McpConfig remote = McpConfig.from(new String[]{"--url", "http://10.0.0.5:9876/trace2local", "--allow-remote"}, Map.of());
        assertThat(remote.allowRemote()).isTrue();
        McpConfig env = McpConfig.from(new String[0], Map.of("TRACE2LOCAL_URL", "http://localhost:19877/trace2local/",
                "TRACE2LOCAL_MCP_ALLOW_MUTATIONS", "true", "TRACE2LOCAL_MCP_DATA", "full"));
        assertThat(env.baseUrl().toString()).isEqualTo("http://localhost:19877/trace2local");
        assertThat(env.allowMutations()).isTrue();
        assertThat(env.dataMode()).isEqualTo(McpConfig.DataMode.FULL);
        assertThatThrownBy(() -> McpConfig.from(new String[]{"--data", "tudo"}, Map.of())).hasMessageContaining("structural ou full");
    }

    @Test
    void stdioTransportSpeaksOneMessagePerLine() throws Exception {
        String in = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "isto não é json",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"status\",\"arguments\":{}}}") + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StdioTransport(server(false, null), new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), out).run();
        List<JsonNode> lines = new ArrayList<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
            lines.add(Json.parse(l));
        }
        assertThat(lines).hasSize(4); // initialize, erro de parse, tools/list, tools/call (notificação sem resposta)
        assertThat(lines.get(0).path("id").asInt()).isEqualTo(1);
        assertThat(lines).anyMatch(l -> l.path("error").path("code").asInt() == -32700);
        assertThat(lines).anyMatch(l -> l.path("id").asInt() == 3 && text(l.path("result")).contains("modo companion"));
    }

    @Test
    void streamableHttpIsLoopbackOnlyWithOriginCheckAndOptionalToken() throws Exception {
        McpConfig cfg = new McpConfig(fake.base(), null, false, null, false, 0, "s3gr3d0", Duration.ofSeconds(5), 60_000);
        HttpTransport http = new HttpTransport(new McpServer(cfg), cfg);
        int port = http.start(0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
            URI uri = URI.create("http://127.0.0.1:" + port + "/mcp");
            HttpResponse<String> noToken = c.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(init)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(noToken.statusCode()).isEqualTo(401);
            HttpResponse<String> ok = c.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer s3gr3d0")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(init)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(Json.parse(ok.body()).path("result").path("serverInfo").path("name").asText()).isEqualTo("trace2local");
            assertThat(ok.headers().firstValue("MCP-Protocol-Version")).hasValue("2025-06-18");
            HttpResponse<String> evil = c.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer s3gr3d0")
                    .header("Origin", "https://site-malicioso.example").POST(HttpRequest.BodyPublishers.ofString(init)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(evil.statusCode()).isEqualTo(403);
            HttpResponse<String> note = c.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer s3gr3d0")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(note.statusCode()).isEqualTo(202);
            HttpResponse<String> get = c.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer s3gr3d0").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(get.statusCode()).isEqualTo(405);
        } finally {
            http.stop();
        }
    }

    @Test
    void promptsChainTheToolsWithTheEvidenceDiscipline() {
        McpServer s = server(false, null);
        JsonNode list = rpc(s, "prompts/list", null).path("result").path("prompts");
        assertThat(list).extracting(p -> p.path("name").asText())
                .containsExactly("investigar-falha", "validar-variacoes-de-parceiro", "homologar-execucao");
        JsonNode get = rpc(s, "prompts/get", Json.obj().put("name", "investigar-falha")
                .set("arguments", Json.obj().put("executionId", "ex-fail"))).path("result");
        String text = get.path("messages").path(0).path("content").path("text").asText();
        assertThat(text).contains("diagnose_failure (executionId=ex-fail)").contains("FATO OBSERVADO").contains("HIPÓTESE");
        JsonNode missing = rpc(s, "prompts/get", Json.obj().put("name", "homologar-execucao"));
        assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    private static List<String> names(JsonNode toolsList) {
        List<String> out = new ArrayList<>();
        toolsList.path("result").path("tools").forEach(t -> out.add(t.path("name").asText()));
        return out;
    }
}
