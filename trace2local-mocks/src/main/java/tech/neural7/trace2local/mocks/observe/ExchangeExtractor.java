package tech.neural7.trace2local.mocks.observe;

import tech.neural7.trace2local.internal.ExecutionStore;
import tech.neural7.trace2local.mocks.spi.ObservedExchange;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionSummary;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.otel.OtelAttributeNames;
import tech.neural7.trace2local.otel.Trace2LocalAttributes;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converte nós {@code HTTP_CLIENT} das execuções em {@link ObservedExchange} —
 * a matéria-prima do replay ({@code source=observed}) e do conselheiro de mocks.
 * Só lê o que já está no modelo (payload redigido na origem, ADR-007).
 */
public final class ExchangeExtractor {

    private ExchangeExtractor() {}

    /** Trocas das execuções mais recentes do store (mais recentes primeiro). */
    public static List<ObservedExchange> fromStore(ExecutionStore store, int executions) {
        List<ObservedExchange> out = new ArrayList<>();
        for (ExecutionSummary s : store.recent(executions)) {
            store.get(s.executionId()).ifPresent(e -> out.addAll(fromExecution(e)));
        }
        return out;
    }

    public static List<ObservedExchange> fromExecution(Execution e) {
        List<ObservedExchange> out = new ArrayList<>();
        String flow = e.roots().isEmpty() ? "?" : e.roots().get(0).label();
        for (Node root : e.roots()) {
            walk(e, flow, root, out);
        }
        return out;
    }

    private static void walk(Execution e, String flow, Node n, List<ObservedExchange> out) {
        if (n.kind() == NodeKind.HTTP_CLIENT) {
            ObservedExchange x = toExchange(e, flow, n);
            if (x != null) {
                out.add(x);
            }
        }
        if (n.children() != null) {
            for (Node c : n.children()) {
                walk(e, flow, c, out);
            }
        }
    }

    static ObservedExchange toExchange(Execution e, String flow, Node n) {
        Map<String, String> a = n.attributes() == null ? Map.of() : n.attributes();
        String host = a.get(OtelAttributeNames.SERVER_ADDRESS);
        int port = parseInt(a.get(OtelAttributeNames.SERVER_PORT), -1);
        String url = a.get(OtelAttributeNames.URL_FULL);
        String route = null;
        if (url != null) {
            try {
                URI u = URI.create(url.replace("{", "%7B").replace("}", "%7D").replace("…", ""));
                route = u.getPath() == null ? null : u.getPath().replace("%7B", "{").replace("%7D", "}");
                if (host == null) {
                    host = u.getHost();
                }
                if (port < 0) {
                    port = u.getPort();
                }
            } catch (IllegalArgumentException ignored) {
                // URL fora do padrão: tenta pelo rótulo
            }
        }
        String method = a.getOrDefault(OtelAttributeNames.HTTP_METHOD, "GET").toUpperCase(Locale.ROOT);
        if (route == null || route.isEmpty()) {
            route = routeFromLabel(n.label());
        }
        if (host == null) {
            return null; // sem host não há o que substituir
        }
        int status = parseInt(a.get(OtelAttributeNames.HTTP_STATUS), -1);
        Duration d = n.totalTime() == null ? Duration.ZERO : n.totalTime();
        return new ObservedExchange(e.executionId(), n.nodeId(), flow, a.get(OtelAttributeNames.PEER_SERVICE),
                host.toLowerCase(Locale.ROOT), port, method, route == null ? "/" : route, status,
                n.payload() == null ? null : n.payload().request(),
                n.payload() == null ? null : n.payload().response(),
                d, n.startedAt(),
                n.error() == null ? null : n.error().type(),
                n.error() == null ? null : n.error().message(),
                simulated(a.get(Trace2LocalAttributes.MOCK)));
    }

    /** Resposta simulada? Repasse SEM variação é a resposta real (passou pelo Mock Connect intacta). */
    static boolean simulated(String marker) {
        if (marker == null) {
            return false;
        }
        return !(marker.contains("passthrough=true") && !marker.contains("variation="));
    }

    private static String routeFromLabel(String label) {
        if (label == null) {
            return "/";
        }
        int slash = label.indexOf('/');
        return slash >= 0 ? label.substring(slash).split(" ")[0] : "/";
    }

    private static int parseInt(String v, int fallback) {
        try {
            return v == null ? fallback : (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
