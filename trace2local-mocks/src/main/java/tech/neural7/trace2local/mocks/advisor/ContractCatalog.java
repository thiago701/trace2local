package tech.neural7.trace2local.mocks.advisor;

import tech.neural7.trace2local.mocks.openapi.OpenApiDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Catálogo de contratos OpenAPI dos parceiros: varre {@code TRACE2LOCAL_MOCKS_DIR}
 * (até 3 níveis) e indexa cada contrato pelos hosts de {@code servers[]} e da
 * extensão {@code x-trace2local-hosts}. Relido no máximo a cada 5 s.
 */
public final class ContractCatalog {

    /** Contrato indexado. {@code path} é relativo ao diretório de mocks (vai direto para {@code source.spec}). */
    public record Contract(String path, OpenApiDocument document) {}

    private final Path root;
    private volatile Map<String, Contract> byHost = Map.of();
    private volatile List<Contract> all = List.of();
    private volatile long loadedAt;

    public ContractCatalog(Path root) {
        this.root = root;
    }

    public Optional<Contract> forHost(String host, int port) {
        refresh();
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        Contract c = port > 0 ? byHost.get(h + ":" + port) : null;
        return Optional.ofNullable(c != null ? c : byHost.get(h));
    }

    public List<Contract> all() {
        refresh();
        return all;
    }

    private void refresh() {
        if (root == null || System.currentTimeMillis() - loadedAt < 5_000) {
            return;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAt < 5_000) {
                return;
            }
            Map<String, Contract> index = new LinkedHashMap<>();
            List<Contract> list = new ArrayList<>();
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root, 3)) {
                    for (Path f : files.filter(ContractCatalog::looksLikeSpec).sorted().toList()) {
                        try {
                            OpenApiDocument doc = OpenApiDocument.load(f);
                            Contract c = new Contract(root.relativize(f).toString().replace('\\', '/'), doc);
                            list.add(c);
                            doc.hosts().forEach(h -> index.putIfAbsent(h.toLowerCase(Locale.ROOT), c));
                        } catch (IOException | RuntimeException notASpec) {
                            // mapping do WireMock, export, estado... não é contrato
                        }
                    }
                } catch (IOException ignored) {
                    // diretório sumiu: catálogo vazio
                }
            }
            byHost = index;
            all = List.copyOf(list);
            loadedAt = System.currentTimeMillis();
        }
    }

    private static boolean looksLikeSpec(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(p) && !n.startsWith(".") && !p.toString().contains("mappings")
                && (n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json"));
    }
}
