package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Ferramenta MCP: definição (nome, descrição para o modelo, schema de entrada, anotações) e
 * execução. {@code mutating} = só listada/executável com {@code --allow-mutations}.
 */
record Tool(String name, String title, String description, ObjectNode inputSchema, boolean readOnly,
            boolean destructive, boolean idempotent, boolean mutating, Handler handler) {

    @FunctionalInterface
    interface Handler {
        Result call(JsonNode arguments) throws Trace2LocalClient.ApiException;
    }

    /** Resultado de {@code tools/call}: texto para o modelo + estrutura opcional para encadear. */
    record Result(String text, JsonNode structured, boolean error) {
        static Result ok(String text) {
            return new Result(text, null, false);
        }

        static Result ok(String text, JsonNode structured) {
            return new Result(text, structured, false);
        }

        static Result fail(String text) {
            return new Result(text, null, true);
        }
    }

    ObjectNode definition() {
        ObjectNode d = Json.obj().put("name", name).put("title", title).put("description", description);
        d.set("inputSchema", inputSchema);
        d.putObject("annotations")
                .put("title", title)
                .put("readOnlyHint", readOnly)
                .put("destructiveHint", destructive)
                .put("idempotentHint", idempotent)
                // fala só com o Trace2Local local (e, nas mutações, com o app do dev)
                .put("openWorldHint", false);
        return d;
    }
}
