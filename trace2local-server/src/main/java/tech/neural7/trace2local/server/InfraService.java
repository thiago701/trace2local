package tech.neural7.trace2local.server;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.ExecutionStore;
import tech.neural7.trace2local.model.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Serviço do catálogo de infra (aba INFRA da UI): escaneia os diretórios
 * configurados ({@code trace2local.infra.scan-dirs}), extrai URLs/ARNs/envs/
 * recursos Terraform com fonte (arquivo:linha) e CRUZA com o acervo —
 * para cada recurso, quais execuções/nós o utilizaram. Cache de 5 s.
 */
public final class InfraService {

    private static final long CACHE_MS = 5_000;

    private final InfraIndexer indexer = new InfraIndexer();
    private final ExecutionStore store;
    private final Trace2LocalConfig cfg;
    private volatile JsonNode cache;
    private volatile long cachedAt;

    public InfraService(ExecutionStore store, Trace2LocalConfig cfg) {
        this.store = store;
        this.cfg = cfg;
    }

    public JsonNode snapshot() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cachedAt < CACHE_MS) {
            return cache;
        }
        var root = JsonCodec.MAPPER.createObjectNode();
        List<String> dirs = scanDirs();
        var dirsNode = root.putArray("scannedDirs");
        dirs.forEach(dirsNode::add);

        List<InfraIndexer.Entry> entries = indexer.scan(dirs.stream().map(Path::of).toList());
        var entriesNode = root.putArray("entries");
        for (InfraIndexer.Entry entry : entries) {
            var e = entriesNode.addObject();
            e.put("type", entry.type());
            e.put("name", entry.name());
            e.put("value", entry.value());
            var sources = e.putArray("sources");
            for (InfraIndexer.Source source : entry.sources()) {
                sources.addObject()
                        .put("file", source.file())
                        .put("line", source.line())
                        .put("kind", source.kind());
            }
            var usages = e.putArray("usedBy");
            int[] total = { 0 };
            for (var summary : store.recent(100)) {
                var execution = store.get(summary.executionId());
                if (execution.isEmpty()) {
                    continue;
                }
                for (Node node : execution.get().roots() != null
                        ? execution.get().roots() : List.<Node>of()) {
                    match(summary.executionId(), node, entry, usages, total, 15);
                }
            }
            e.put("usedByTotal", total[0]);
        }
        root.put("entriesCount", entries.size());
        cache = root;
        cachedAt = now;
        return cache;
    }

    private void match(String executionId, Node node, InfraIndexer.Entry entry,
                       com.fasterxml.jackson.databind.node.ArrayNode usages, int[] total, int cap) {
        String value = entry.value() != null ? entry.value() : "";
        String name = entry.name() != null ? entry.name() : "";
        boolean matches = false;
        if (!value.isBlank()) {
            String haystack = node.label()
                    + "|" + String.join("|",
                    (node.attributes() != null ? node.attributes() : Map.<String, String>of()).values());
            matches = haystack.contains(value);
        }
        if (!matches && !name.isBlank() && node.label() != null && node.label().contains(name)) {
            matches = true;
        }
        total[0] += matches ? 1 : 0;
        if (matches && usages.size() < cap) {
            usages.addObject()
                    .put("executionId", executionId)
                    .put("nodeId", node.nodeId() != null ? node.nodeId() : "")
                    .put("label", node.label() != null ? node.label() : "")
                    .put("kind", node.kind() != null ? node.kind().name() : "UNKNOWN");
        }
        for (Node child : node.children() != null ? node.children() : List.<Node>of()) {
            match(executionId, child, entry, usages, total, cap);
        }
    }

    private List<String> scanDirs() {
        String raw = cfg.infraScanDirs();
        if (raw == null || raw.isBlank()) {
            return List.of("terraform", "infra");
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
