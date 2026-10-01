package tech.neural7.trace2local.mocks.plugins.sink;

import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.json.WireMockFormat;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.spi.DeployRequest;
import tech.neural7.trace2local.mocks.spi.MockPluginException;
import tech.neural7.trace2local.mocks.spi.StubSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * {@code file}: exporta os stubs (já com as variações assadas) como mappings do
 * WireMock em {@code <dir>/<binding>/mappings/*.json} — para versionar no repo e
 * reusar em testes de integração/CI. Só escreve dentro de {@code TRACE2LOCAL_MOCKS_DIR}.
 */
public final class FileExportSink implements StubSink {

    private final Function<String, java.util.Optional<Path>> resolver;

    public FileExportSink(Function<String, java.util.Optional<Path>> resolver) {
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public String description() {
        return "Exporta os stubs com as variações aplicadas como mappings do WireMock (versionáveis, prontos para CI).";
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef().define("dir", Type.STRING, "exports", null, Importance.HIGH,
                "diretório de saída, relativo a TRACE2LOCAL_MOCKS_DIR");
    }

    @Override
    public boolean dynamic() {
        return false;
    }

    @Override
    public Deployment deploy(DeployRequest request, MockConfig config) {
        Path dir = target(request.binding(), config);
        try {
            undeploy(request.binding(), config);
            Files.createDirectories(dir);
            int i = 0;
            for (Stub stub : request.stubs()) {
                Path file = dir.resolve(String.format("%03d-%s.json", i++, stub.id().replaceAll("[^A-Za-z0-9._-]", "_")));
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, JsonSupport.writePretty(WireMockFormat.write(stub, request.binding(), 0)));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return new Deployment("file:" + config.getString("dir") + "/" + request.binding() + "/mappings",
                    i + " mapping(s) exportados", List.of());
        } catch (IOException e) {
            throw new MockPluginException("falha ao exportar para " + config.getString("dir") + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void undeploy(String binding, MockConfig config) {
        Path dir = target(binding, config);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            throw new MockPluginException("falha ao limpar " + dir.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private Path target(String binding, MockConfig config) {
        String rel = config.getString("dir") + "/" + binding + "/mappings";
        return resolver.apply(rel).orElseThrow(() -> new MockPluginException(
                "exportação indisponível: defina TRACE2LOCAL_MOCKS_DIR e use caminho relativo dentro dele"));
    }
}
