package tech.neural7.trace2local.mocks.plugins.source;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.json.WireMockFormat;
import tech.neural7.trace2local.mocks.model.Fault;
import tech.neural7.trace2local.mocks.model.MockResponse;
import tech.neural7.trace2local.mocks.model.RequestMatcher;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.spi.MockPluginException;
import tech.neural7.trace2local.mocks.spi.SourceContext;
import tech.neural7.trace2local.mocks.spi.StubSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code inline}: stubs escritos à mão (formato curto) ou mappings do WireMock —
 * inline no config ({@code stubs}) ou num diretório ({@code dir}, relativo ao
 * diretório de mocks). Reaproveita os mocks que o time já versiona.
 *
 * <p>Formato curto: {@code [{"method":"GET","path":"/entries/{key}","status":200,"body":{...}}]}.
 */
public final class InlineStubSource implements StubSource {

    @Override
    public String name() {
        return "inline";
    }

    @Override
    public String description() {
        return "Stubs escritos à mão (formato curto) ou mappings do WireMock, inline ou de um diretório versionado.";
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef()
                .define("stubs", Type.JSON, null, null, Importance.HIGH,
                        "array de stubs (formato curto ou mappings do WireMock)")
                .define("dir", Type.STRING, null, null, Importance.MEDIUM,
                        "diretório com mappings *.json do WireMock, relativo a TRACE2LOCAL_MOCKS_DIR");
    }

    @Override
    public List<Stub> load(MockConfig config, SourceContext context) {
        List<Stub> out = new ArrayList<>();
        JsonNode inline = config.getJson("stubs");
        if (inline != null && !inline.isNull()) {
            out.addAll(parse(inline, "inline"));
        }
        String dir = config.getString("dir");
        if (dir != null && !dir.isBlank()) {
            Path root = context.resolve(dir).orElseThrow(() -> new MockPluginException(
                    "diretório '" + dir + "' indisponível: defina TRACE2LOCAL_MOCKS_DIR e use caminho relativo dentro dele"));
            if (!Files.isDirectory(root)) {
                throw new MockPluginException("diretório não encontrado: " + dir);
            }
            try (Stream<Path> files = Files.walk(root, 3)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    JsonNode doc = JsonSupport.MAPPER.readTree(Files.readString(f));
                    out.addAll(parse(doc, "file:" + root.relativize(f)));
                }
            } catch (IOException e) {
                throw new MockPluginException("falha ao ler mappings em " + dir + ": " + e.getMessage(), e);
            }
        }
        if (out.isEmpty()) {
            throw new MockPluginException("nenhum stub: informe 'stubs' (inline) ou 'dir' (mappings do WireMock)");
        }
        return out;
    }

    static List<Stub> parse(JsonNode doc, String origin) {
        if (doc.has("mappings") || doc.has("request") || doc.isArray() && !doc.isEmpty() && doc.get(0).has("request")) {
            return WireMockFormat.read(doc, origin);
        }
        List<Stub> out = new ArrayList<>();
        if (!doc.isArray()) {
            throw new MockPluginException("'stubs' deve ser um array");
        }
        int i = 0;
        for (JsonNode s : doc) {
            String method = s.path("method").asText("GET");
            String path = s.path("path").asText("/**");
            Map<String, String> headers = new LinkedHashMap<>();
            s.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
            JsonNode body = s.get("body");
            String text = null;
            if (body != null && !body.isNull()) {
                if (body.isTextual()) {
                    text = body.asText();
                } else {
                    text = JsonSupport.write(body);
                    headers.putIfAbsent("Content-Type", "application/json");
                }
            }
            MockResponse response = new MockResponse(s.path("status").asInt(200), headers, text,
                    s.path("delay.ms").asLong(0), Fault.NONE);
            String id = s.path("id").asText("inline-" + i);
            out.add(new Stub(id, s.path("name").asText(method + " " + path), RequestMatcher.of(method, path),
                    response, s.path("priority").asInt(5), origin, List.of()));
            i++;
        }
        return out;
    }
}
