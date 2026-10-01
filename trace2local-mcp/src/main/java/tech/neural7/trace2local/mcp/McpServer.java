package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Núcleo do protocolo (JSON-RPC 2.0 do Model Context Protocol), independente de transporte:
 * {@code initialize}, {@code ping}, {@code tools/list}, {@code tools/call}, {@code prompts/list},
 * {@code prompts/get}. Erro de ferramenta vira resultado com {@code isError} (o modelo lê e se
 * corrige); erro de protocolo vira erro JSON-RPC.
 */
public final class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());

    /** Versões do protocolo aceitas, da mais nova para a mais antiga. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    static final String SERVER_NAME = "trace2local";
    static final String VERSION = versionOf();

    private final McpConfig config;
    private final Tools tools;
    private final Prompts prompts = new Prompts();
    private volatile String negotiated = PROTOCOL_VERSIONS.get(0);

    public McpServer(McpConfig config) {
        this(config, new Trace2LocalClient(config));
    }

    McpServer(McpConfig config, Trace2LocalClient client) {
        this.config = config;
        this.tools = new Tools(client, config);
    }

    /** Processa uma mensagem (ou lote); devolve a resposta, ou {@code null} para notificações. */
    public JsonNode handle(JsonNode message) {
        if (message != null && message.isArray()) {
            ArrayNode out = Json.arr();
            for (JsonNode m : message) {
                JsonNode r = handleOne(m);
                if (r != null) {
                    out.add(r);
                }
            }
            return out.isEmpty() ? null : out;
        }
        return handleOne(message);
    }

    private JsonNode handleOne(JsonNode msg) {
        if (msg == null || !msg.isObject() || !"2.0".equals(msg.path("jsonrpc").asText())) {
            return error(null, -32600, "requisição JSON-RPC 2.0 inválida");
        }
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText(null);
        if (method == null) {
            // resposta do cliente a algo que não pedimos (não usamos sampling/elicitation): ignora
            return null;
        }
        boolean notification = id == null || id.isNull();
        try {
            JsonNode result = switch (method) {
                case "initialize" -> initialize(msg.path("params"));
                case "ping" -> Json.obj();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(msg.path("params"));
                case "prompts/list" -> promptsList();
                case "prompts/get" -> promptsGet(msg.path("params"));
                case "resources/list" -> Json.obj().set("resources", Json.arr());
                case "resources/templates/list" -> Json.obj().set("resourceTemplates", Json.arr());
                case "notifications/initialized", "notifications/cancelled", "notifications/roots/list_changed" -> null;
                default -> {
                    if (method.startsWith("notifications/")) {
                        yield null;
                    }
                    throw new RpcError(-32601, "método não suportado: " + method);
                }
            };
            if (notification) {
                return null;
            }
            return result == null ? null : response(id, result);
        } catch (RpcError e) {
            return notification ? null : error(id, e.code, e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "falha interna em " + method, e);
            return notification ? null : error(id, -32603, "erro interno: " + e.getClass().getSimpleName());
        }
    }

    private JsonNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        negotiated = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0);
        ObjectNode r = Json.obj().put("protocolVersion", negotiated);
        ObjectNode caps = r.putObject("capabilities");
        caps.putObject("tools").put("listChanged", false);
        caps.putObject("prompts").put("listChanged", false);
        r.putObject("serverInfo").put("name", SERVER_NAME).put("title", "Trace2Local").put("version", VERSION);
        r.put("instructions", instructions());
        return r;
    }

    String negotiatedVersion() {
        return negotiated;
    }

    private String instructions() {
        return "Trace2Local é a observabilidade LOCAL do app do dev (Java, Lambda, LocalStack): cada requisição vira uma "
                + "árvore de execução com dados alterados, logs, insights e mocks de parceiros. Comece por status. "
                + "Falhou? diagnose_failure → get_step → compare_executions. Entender um fluxo? list_executions → "
                + "get_execution → explain_execution. Parceiro fora do ar ou variação de resposta? list_mock_suggestions"
                + (config.allowMutations() ? " → apply_mock_suggestion → dispatch_endpoint." : " (mutações desligadas: proponha ao dev).")
                + " Cite executionId e [nodeId] como evidência; separe fato observado de hipótese. Dados: "
                + config.dataMode().name().toLowerCase(java.util.Locale.ROOT) + ".";
    }

    private JsonNode toolsList() {
        ArrayNode list = Json.arr();
        tools.visible().forEach(t -> list.add(t.definition()));
        return Json.obj().set("tools", list);
    }

    private JsonNode toolsCall(JsonNode params) {
        String name = params.path("name").asText(null);
        if (name == null) {
            throw new RpcError(-32602, "tools/call sem name");
        }
        Tool tool = tools.find(name);
        if (tool == null) {
            if (tools.isBlockedMutation(name)) {
                return toolResult(Tool.Result.fail("'" + name + "' altera o app ou os mocks e está desligada. O dev liga com "
                        + "TRACE2LOCAL_MCP_ALLOW_MUTATIONS=true (ou --allow-mutations). Enquanto isso, proponha o passo para ele executar."));
            }
            throw new RpcError(-32602, "ferramenta desconhecida: " + name);
        }
        Tool.Result result;
        try {
            result = tool.handler().call(params.path("arguments"));
        } catch (Trace2LocalClient.ApiException e) {
            result = Tool.Result.fail(e.getMessage());
        } catch (IllegalArgumentException e) {
            result = Tool.Result.fail("argumento inválido: " + e.getMessage());
        }
        return toolResult(result);
    }

    private JsonNode toolResult(Tool.Result r) {
        String text = r.text() == null ? "" : r.text();
        int max = config.maxOutputChars();
        if (text.length() > max) {
            text = text.substring(0, max) + "\n… [saída truncada em " + max + " caracteres — refine o filtro/limite]";
        }
        ObjectNode out = Json.obj();
        out.putArray("content").addObject().put("type", "text").put("text", text);
        if (r.structured() != null && r.structured().isObject() && Json.write(r.structured()).length() <= max * 2) {
            out.set("structuredContent", r.structured());
        }
        out.put("isError", r.error());
        return out;
    }

    private JsonNode promptsList() {
        ArrayNode list = Json.arr();
        prompts.all().forEach(p -> list.add(Prompts.definition(p)));
        return Json.obj().set("prompts", list);
    }

    private JsonNode promptsGet(JsonNode params) {
        String name = params.path("name").asText("");
        Prompts.Prompt p = prompts.find(name);
        if (p == null) {
            throw new RpcError(-32602, "prompt desconhecido: " + name);
        }
        JsonNode args = params.path("arguments");
        for (String[] a : p.arguments()) {
            if (Boolean.parseBoolean(a[2]) && args.path(a[0]).asText("").isBlank()) {
                throw new RpcError(-32602, "argumento obrigatório do prompt: " + a[0]);
            }
        }
        ObjectNode r = Json.obj().put("description", p.description());
        r.putArray("messages").addObject().put("role", "user")
                .putObject("content").put("type", "text").put("text", p.template().render(args));
        return r;
    }

    // ----------------------------------------------------------------- JSON-RPC

    static ObjectNode response(JsonNode id, JsonNode result) {
        ObjectNode r = Json.obj().put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = Json.obj().put("jsonrpc", "2.0");
        r.set("id", id == null ? Json.MAPPER.nullNode() : id);
        r.putObject("error").put("code", code).put("message", message);
        return r;
    }

    private static final class RpcError extends RuntimeException {
        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static String versionOf() {
        String v = McpServer.class.getPackage() == null ? null : McpServer.class.getPackage().getImplementationVersion();
        return v == null ? "0.1.0-SNAPSHOT" : v;
    }
}
