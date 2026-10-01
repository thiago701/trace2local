package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * API do Trace2Local de mentira (mesmo contrato JSON da real) para testar o servidor MCP sem
 * subir o Station: uma execução de Pix que falhou no SPI (parceiro 503), uma concluída com KYC
 * simulado pelo Mock Connect e consumidor SQS, logs, laudo, sugestões de mock.
 */
final class FakeTrace2Local implements AutoCloseable {

    record Call(String method, String path, String body, String guardHeader) {}

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Map<String, JsonNode> routes = new ConcurrentHashMap<>();
    private final HttpServer server;
    private volatile boolean dispatched;

    FakeTrace2Local() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        seed();
        server.createContext("/trace2local/api", ex -> {
            String path = ex.getRequestURI().getRawPath().substring("/trace2local/api".length());
            String query = ex.getRequestURI().getRawQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(ex.getRequestMethod(), path + (query == null ? "" : "?" + query), body,
                    ex.getRequestHeaders().getFirst("X-Trace2Local")));
            JsonNode out;
            int status = 200;
            if (!ex.getRequestMethod().equals("GET") && !"1".equals(ex.getRequestHeaders().getFirst("X-Trace2Local"))) {
                status = 403;
                out = Json.obj().put("error", "mutação sem X-Trace2Local");
            } else if (path.equals("/execute")) {
                dispatched = true;
                out = Json.obj().putNull("executionId").put("traceId", "cccccccccccccccccccccccccccccccc");
            } else if (path.equals("/executions")) {
                out = executions();
            } else {
                out = routes.get(ex.getRequestMethod() + " " + path);
                if (out == null) {
                    out = routes.get(path);
                }
                if (out == null) {
                    status = 404;
                    out = Json.obj().put("error", "não encontrado");
                }
            }
            byte[] bytes = Json.write(out).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(bytes);
            }
        });
        server.start();
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/trace2local");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private JsonNode executions() {
        ArrayNode list = Json.arr();
        if (dispatched) {
            list.add(summary("ex-new", "cccccccccccccccccccccccccccccccc", "COMPLETED", "pix-api · GET /pix/transfers/{transferId}", 2, 12));
        }
        list.add(summary("ex-fail", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "FAILED", "pix-api · POST /pix/transfers", 6, 640));
        list.add(summary("ex-ok", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "COMPLETED", "pix-api · POST /pix/transfers", 7, 320));
        return list;
    }

    private static ObjectNode summary(String id, String trace, String status, String label, int nodes, long dur) {
        return Json.obj().put("executionId", id).put("traceId", trace).put("status", status).put("trigger", "LAMBDA_EVENT")
                .put("startedAt", "2026-10-01T10:00:00Z").put("duration", dur).put("rootLabel", label).put("nodeCount", nodes);
    }

    private static ObjectNode node(String id, String kind, String label, String status, long start, long total) {
        ObjectNode n = Json.obj().put("nodeId", id).put("kind", kind).put("label", label).put("status", status)
                .put("startedAt", java.time.Instant.parse("2026-10-01T10:00:00Z").plusMillis(start).toString())
                .put("selfTime", Math.max(1, total / 3)).put("totalTime", total);
        n.putObject("attributes");
        n.putNull("payload");
        n.putNull("mutation");
        n.putNull("error");
        n.putArray("children");
        return n;
    }

    private static ObjectNode add(ObjectNode parent, ObjectNode child) {
        child.put("parentId", parent.path("nodeId").asText());
        ((ArrayNode) parent.get("children")).add(child);
        return child;
    }

    private void seed() {
        routes.put("/meta", Json.obj().put("app", "station").put("mode", "companion").put("version", "0.1.0").put("runtime", "21")
                .set("capabilities", Json.arr().add("endpoints").add("execute").add("mocks")));
        routes.put("/health", Json.obj().put("bufferUsage", "0%").put("liveExecutions", 0).put("logLines", 12).put("dropped", 0));
        routes.put("/intelligence", Json.obj().set("engine", Json.obj().put("effectiveMode", "deterministic").put("egress", "structural")));

        // ---- execução que falhou: API → SQS → liquidação → SPI 503
        ObjectNode root = node("n-root", "LAMBDA", "pix-api · POST /pix/transfers", "OK", 0, 120);
        ObjectNode biz = add(root, node("n-accept", "BUSINESS", "Aceitar e enviar para liquidação", "OK", 60, 40));
        ObjectNode sqs = add(biz, node("n-sqs", "SQS", "SQS: pix-settlement", "OK", 70, 10));
        ObjectNode consumer = add(sqs, node("n-settle", "LAMBDA", "pix-settlement · SQS pix-settlement", "ERROR", 1180, 300));
        consumer.putObject("error").put("type", "PartnerUnavailableException").put("message", "SPI (BACEN) indisponível: HTTP 503")
                .put("stack", "tech.neural7.pix.partners.SpiClient.settle(SpiClient.java:42)\n\tat tech.neural7.pix.settlement.Handler.handle(Handler.java:31)");
        ObjectNode liquidar = add(consumer, node("n-liquidar", "BUSINESS", "Liquidar no SPI", "ERROR", 1190, 250));
        liquidar.putObject("error").put("type", "PartnerUnavailableException").put("message", "SPI (BACEN) indisponível: HTTP 503");
        ObjectNode spi = add(liquidar, node("n-spi", "HTTP_CLIENT", "POST SPI (BACEN) /spi/v1/settlements", "ERROR", 1200, 200));
        spi.putObject("error").put("type", "PartnerUnavailableException").put("message", "SPI (BACEN) indisponível: HTTP 503")
                .put("stack", "tech.neural7.pix.partners.SpiClient.settle(SpiClient.java:42)");
        ((ObjectNode) spi.get("attributes")).put("url.full", "http://spi.bacen.local:8080/spi/v1/settlements")
                .put("t2l.payload.request", "{\"endToEndId\":\"E123\"}");
        spi.putObject("payload").put("request", "{\"endToEndId\":\"E123\",\"amount\":150.0}").putNull("response");
        ObjectNode ddb = add(root, node("n-ddb", "DYNAMODB", "DynamoDB: pix-transfers", "OK", 20, 15));
        ddb.putObject("mutation").put("kind", "CREATE").put("target", "pix-transfers").put("key", "pix-1").put("fidelity", "EXACT")
                .set("deltas", Json.arr().add(Json.obj().put("path", "status").putNull("before").put("after", "ACCEPTED")));
        ObjectNode fail = Json.obj().put("executionId", "ex-fail").put("traceId", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("status", "FAILED")
                .put("trigger", "LAMBDA_EVENT").put("startedAt", "2026-10-01T10:00:00Z").put("duration", 1500);
        fail.putArray("roots").add(root);
        fail.putObject("metrics").put("nodeCount", 6).put("maxDepth", 5).put("lostSpans", 0).put("droppedEvents", 0);
        fail.putArray("warnings");
        routes.put("/executions/ex-fail", fail);

        // ---- execução concluída: KYC simulado, sem consumidor com erro
        ObjectNode r2 = node("m-root", "LAMBDA", "pix-api · POST /pix/transfers", "OK", 0, 120);
        ObjectNode kyc = add(r2, node("m-kyc", "HTTP_CLIENT", "GET KYC & Limites /v2/customers/{id}/limits", "OK", 10, 6));
        ((ObjectNode) kyc.get("attributes")).put("t2l.mock", "binding=mock-kyc-limites; stub=consultarLimites")
                .put("url.full", "http://kyc.bureau.local:8080/v2/customers/1/limits");
        ObjectNode accept2 = add(r2, node("m-accept", "BUSINESS", "Aceitar e enviar para liquidação", "OK", 60, 40));
        ObjectNode sqs2 = add(accept2, node("m-sqs", "SQS", "SQS: pix-settlement", "OK", 70, 10));
        add(sqs2, node("m-settle", "LAMBDA", "pix-settlement · SQS pix-settlement", "OK", 1180, 30));
        ObjectNode ok = Json.obj().put("executionId", "ex-ok").put("traceId", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").put("status", "COMPLETED")
                .put("trigger", "LAMBDA_EVENT").put("startedAt", "2026-10-01T09:59:00Z").put("duration", 1210);
        ok.putArray("roots").add(r2);
        ok.putObject("metrics").put("nodeCount", 5).put("maxDepth", 3);
        ok.putArray("warnings");
        routes.put("/executions/ex-ok", ok);

        ObjectNode newExec = Json.obj().put("executionId", "ex-new").put("traceId", "cccccccccccccccccccccccccccccccc").put("status", "COMPLETED")
                .put("trigger", "LAMBDA_EVENT").put("startedAt", "2026-10-01T10:01:00Z").put("duration", 12);
        newExec.putArray("roots").add(node("x-root", "LAMBDA", "pix-api · GET /pix/transfers/{transferId}", "OK", 0, 12));
        newExec.putArray("warnings");
        routes.put("/executions/ex-new", newExec);

        // ---- logs, laudo, sugestões
        ObjectNode logs = Json.obj();
        logs.putArray("lines")
                .add(Json.obj().put("offsetMs", 1180).put("level", "PLATFORM").put("message", "START RequestId: r-2 Version: $LATEST").put("requestId", "r-2").put("logGroup", "/aws/lambda/pix-settlement"))
                .add(Json.obj().put("offsetMs", 1201).put("level", "WARN").put("message", "SPI respondeu 503").put("spanId", "n-spi").put("requestId", "r-2"))
                .add(Json.obj().put("offsetMs", 1400).put("level", "ERROR").put("message", "liquidação falhou; mensagem volta para a fila").put("spanId", "n-settle").put("requestId", "r-2"))
                .add(Json.obj().put("offsetMs", 1470).put("level", "PLATFORM").put("message", "END RequestId: r-2").put("requestId", "r-2"));
        routes.put("/executions/ex-fail/logs", logs);
        ObjectNode assist = Json.obj();
        ObjectNode exe = assist.putObject("executive").put("headline", "Falha técnica em pix-api · POST /pix/transfers — bloqueada")
                .put("summary", "6 passos; desfecho falha técnica.");
        exe.putObject("outcome").put("label", "Falha técnica").put("confidence", 0.93).put("engine", "jev-deterministic").put("rationale", "PartnerUnavailableException");
        exe.putObject("risk").put("label", "alto").put("confidence", 0.75).put("engine", "jev-deterministic").put("rationale", "passo com erro");
        exe.putObject("readiness").put("label", "bloqueada").put("confidence", 0.7).put("engine", "jev-deterministic").put("rationale", "falhou");
        exe.putArray("rules").add(Json.obj().put("id", "R1").put("label", "Violada").put("term", "pix-settlement")
                .put("text", "só debita o ledger depois do SPI confirmar SETTLED").put("rationale", "SPI não confirmou")
                .put("engine", "jev-deterministic").put("confidence", 0.9).set("nodeIds", Json.arr().add("n-spi")));
        exe.putArray("checklist").add(Json.obj().put("item", "Fluxo concluiu").put("ok", false).put("detail", "status FAILED"));
        ObjectNode tech = assist.putObject("technical");
        tech.putArray("hotspots").add(Json.obj().put("rank", 1).put("nodeId", "n-spi").put("label", "POST SPI").put("totalMs", 200).put("selfMs", 200).put("failed", true));
        tech.putArray("criticalPath").add("n-root").add("n-settle");
        tech.putArray("anomalies").add(Json.obj().put("severity", "alta").put("title", "Erro em Liquidar no SPI").put("detail", "HTTP 503"));
        assist.putArray("insights").add(Json.obj().put("id", "RES-001").put("severity", "HIGH").put("nature", "HYPOTHESIS").put("confidence", 0.7)
                .put("title", "Chamada ao SPI sem retry nem circuit breaker")
                .set("evidence", Json.arr().add(Json.obj().set("ref", Json.obj().put("executionId", "ex-fail").put("nodeId", "n-spi")))));
        routes.put("/executions/ex-fail/insights", assist);
        routes.put("/executions/ex-fail/story", Json.obj().put("intro", "A execução começou no API Gateway.")
                .put("conclusion", "Terminou com erro.").set("steps", Json.arr().add(Json.obj().put("order", 1).put("text", "Chamada externa POST a SPI (BACEN) (status 503)."))));
        routes.put("/mocks/suggestions", Json.arr().add(Json.obj().put("id", "happy-spi").put("kind", "HAPPY_PATH_ONLY").put("severity", "LOW")
                .put("target", "spi.bacen.local:8080").put("title", "Só o caminho feliz de SPI (BACEN) foi exercitado")
                .put("why", "o SPI só respondeu 2xx").set("variations", Json.arr()
                        .add(Json.obj().put("id", "http-503").put("title", "Indisponível (503)").put("rationale", "parceiro fora do ar").putNull("predicate"))
                        .add(Json.obj().put("id", "falha-transitoria").put("title", "503 só na 1ª chamada").put("rationale", "retry recupera?").put("predicate", "call-count")))));
        routes.put("POST /mocks/suggestions/happy-spi/apply", Json.obj().put("name", "mock-spi")
                .set("status", Json.obj().put("state", "RUNNING").put("stubs", 1).put("endpoint", "http://127.0.0.1:19878/mock-spi")));
        routes.put("/endpoints", Json.arr().add(Json.obj().put("endpointId", "getTransfer").put("method", "GET").put("path", "/pix/transfers/{transferId}")
                .put("handler", "Consulta").set("parameters", Json.arr().add(Json.obj().put("name", "transferId").put("in", "path").put("required", true)))));
    }
}
