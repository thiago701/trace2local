package tech.neural7.trace2local.mocks.plugins.source;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.config.Validators;
import tech.neural7.trace2local.mocks.model.MockResponse;
import tech.neural7.trace2local.mocks.model.RequestMatcher;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.openapi.OpenApiDocument;
import tech.neural7.trace2local.mocks.spi.MockPluginException;
import tech.neural7.trace2local.mocks.spi.SourceContext;
import tech.neural7.trace2local.mocks.spi.StubSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code openapi}: contrato do parceiro → um stub por operação (estilo Prism/Microcks).
 * Corpo: exemplo nomeado ({@code example}) → primeiro exemplo → gerado do schema.
 * É a fonte certa quando a API ainda não existe ou não há acesso a ela no ambiente local.
 */
public final class OpenApiStubSource implements StubSource {

    @Override
    public String name() {
        return "openapi";
    }

    @Override
    public String description() {
        return "Gera stubs a partir do contrato OpenAPI 3 do parceiro (exemplos nomeados ou schema) — API inexistente ou sem acesso local.";
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef()
                .define("spec", Type.STRING, ConfigDef.NO_DEFAULT, Validators.nonEmpty(), Importance.HIGH,
                        "arquivo OpenAPI (YAML/JSON), relativo a TRACE2LOCAL_MOCKS_DIR")
                .define("status", Type.STRING, "2xx", null, Importance.MEDIUM,
                        "resposta a servir: código exato, faixa (2xx, 4xx) ou default", "2xx", "200", "201", "422", "default")
                .define("example", Type.STRING, null, null, Importance.MEDIUM,
                        "nome do exemplo a usar quando a resposta tiver vários")
                .define("operations", Type.LIST, List.of(), null, Importance.LOW,
                        "filtra por operationId ou 'MÉTODO /caminho'; vazio = todas")
                .define("include.server.path", Type.BOOLEAN, true, null, Importance.LOW,
                        "prefixa o caminho-base de servers[0] (ex.: /api/v2) nos stubs");
    }

    @Override
    public List<Stub> load(MockConfig config, SourceContext context) {
        String spec = config.getString("spec");
        Path file = context.resolve(spec).orElseThrow(() -> new MockPluginException(
                "contrato '" + spec + "' indisponível: defina TRACE2LOCAL_MOCKS_DIR e use caminho relativo dentro dele"));
        OpenApiDocument doc;
        try {
            doc = OpenApiDocument.load(file);
        } catch (IOException e) {
            throw new MockPluginException("falha ao ler o contrato " + spec + ": " + e.getMessage(), e);
        }
        return stubs(doc, config.getString("status"), config.getString("example"), config.getList("operations"),
                config.getBoolean("include.server.path"), spec);
    }

    public static List<Stub> stubs(OpenApiDocument doc, String status, String example, List<String> filter,
                                   boolean includeServerPath, String spec) {
        String base = includeServerPath ? doc.basePath() : "";
        List<Stub> out = new ArrayList<>();
        for (OpenApiDocument.Operation op : doc.operations()) {
            if (!filter.isEmpty() && !filter.contains(op.operationId()) && !filter.contains(op.key())) {
                continue;
            }
            String code = doc.pickStatus(op, status);
            if (code == null) {
                continue;
            }
            int httpStatus = code.equals("default") || !code.matches("\\d{3}") ? 200 : Integer.parseInt(code);
            JsonNode body = doc.body(op, code, example);
            List<String> notes = new ArrayList<>();
            if (example != null && !doc.examples(op, code).containsKey(example)) {
                notes.add("exemplo '" + example + "' não existe em " + op.label() + " — usado o primeiro disponível/schema");
            }
            if (doc.examples(op, code).isEmpty() && body != null) {
                notes.add("corpo gerado a partir do schema (sem exemplo no contrato)");
            }
            out.add(new Stub(op.operationId() != null ? op.operationId() : op.key().replaceAll("[^A-Za-z0-9]+", "-"),
                    op.label(), RequestMatcher.of(op.method(), base + op.path()),
                    body == null ? new MockResponse(httpStatus, null, null, 0, null) : MockResponse.json(httpStatus, body),
                    5, "openapi:" + spec + "#" + op.label(), notes));
        }
        if (out.isEmpty()) {
            throw new MockPluginException("nenhuma operação do contrato casou com o filtro/status pedido");
        }
        return out;
    }
}
