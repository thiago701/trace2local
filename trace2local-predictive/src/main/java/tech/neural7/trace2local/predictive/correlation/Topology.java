package tech.neural7.trace2local.predictive.correlation;

import tech.neural7.trace2local.otel.OtelAttributeNames;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.NodeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * TOPOLOGIA observada do ecossistema: componentes (serviço, função, tabela,
 * fila, tópico, rota, parceiro externo) e arestas pai → filho agregadas sobre o
 * acervo. Alimenta o mapa "Anatomia" da UI e os analisadores de arquitetura.
 *
 * <p>Zonas (preparado para FRONTEIRAS EXTERNAS): {@code core} (código da
 * aplicação), {@code boundary} (recursos AWS/LocalStack), {@code external}
 * (parceiros fora da máquina) e {@code declared} (recurso declarado no IaC e
 * nunca observado — preenchido pela camada de servidor).
 */
public final class Topology {

    /** Componente agregado. */
    public static final class Component {
        public final String id;
        public final String kind;
        public final String zone;
        public final String label;
        public int calls;
        public int errors;
        public final List<Long> durations = new ArrayList<>();
        public final Set<String> flows = new TreeSet<>();
        public final Set<String> neighbors = new TreeSet<>();
        public final Set<String> executions = new HashSet<>();

        Component(String id, String kind, String zone, String label) {
            this.id = id;
            this.kind = kind;
            this.zone = zone;
            this.label = label;
        }

        public double p50() {
            return durations.isEmpty() ? 0 : Stats.median(durations);
        }

        public double p95() {
            return durations.isEmpty() ? 0 : Stats.percentile(durations, 95);
        }
    }

    /** Aresta agregada. */
    public static final class Edge {
        public final String from;
        public final String to;
        public final boolean async;
        public int calls;
        public int errors;
        public final List<Long> waits = new ArrayList<>();

        Edge(String from, String to, boolean async) {
            this.from = from;
            this.to = to;
            this.async = async;
        }
    }

    private final Map<String, Component> components = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private final Set<String> flows = new TreeSet<>();
    private int executions;

    public static Topology of(List<Execution> corpus) {
        Topology t = new Topology();
        for (Execution e : corpus) {
            t.add(FlowView.of(e));
        }
        return t;
    }

    public void add(FlowView flow) {
        executions++;
        String flowKey = flow.flowKey();
        flows.add(flowKey);
        Map<String, FlowView.Step> byId = new LinkedHashMap<>();
        flow.steps().forEach(s -> byId.put(s.node().nodeId(), s));
        for (FlowView.Step s : flow.steps()) {
            Component c = components.computeIfAbsent(s.component(), id -> new Component(id, s.kind().name(), zoneOf(s),
                    labelOf(s)));
            c.calls++;
            if (s.failed()) {
                c.errors++;
            }
            c.durations.add(s.durationMs());
            if (c.durations.size() > 500) {
                c.durations.remove(0);
            }
            c.flows.add(flowKey);
            c.executions.add(flow.execution().executionId());
            FlowView.Step parent = s.parentId() != null ? byId.get(s.parentId()) : null;
            if (parent != null && !parent.component().equals(s.component())) {
                boolean async = s.asyncConsumer();
                Edge e = edges.computeIfAbsent(parent.component() + "→" + s.component(),
                        k -> new Edge(parent.component(), s.component(), async));
                e.calls++;
                if (s.failed()) {
                    e.errors++;
                }
                if (async) {
                    e.waits.add(Math.max(0, s.startMs() - parent.endMs()));
                }
                c.neighbors.add(parent.component());
                components.get(parent.component()).neighbors.add(s.component());
            }
        }
    }

    static String zoneOf(FlowView.Step s) {
        NodeKind k = s.kind();
        return switch (k) {
            case DYNAMODB, SQS, SNS, SQL -> "boundary";
            case HTTP_CLIENT -> {
                String host = s.node().attributes() != null ? s.node().attributes().getOrDefault(OtelAttributeNames.SERVER_ADDRESS, "") : "";
                yield host.isBlank() || host.equals("localhost") || host.startsWith("127.") || host.equals("localstack")
                        || host.equals("host.docker.internal") ? "boundary" : "external";
            }
            default -> "core";
        };
    }

    private static String labelOf(FlowView.Step s) {
        String c = s.component();
        int i = c.indexOf(':');
        return i > 0 ? c.substring(i + 1) : c;
    }

    public Map<String, Component> components() {
        return components;
    }

    public Map<String, Edge> edges() {
        return edges;
    }

    public Set<String> flows() {
        return flows;
    }

    public int executions() {
        return executions;
    }
}
