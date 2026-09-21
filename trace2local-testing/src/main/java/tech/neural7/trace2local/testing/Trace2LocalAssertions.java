package tech.neural7.trace2local.testing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.MutationFidelity;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;
import tech.neural7.trace2local.model.Trigger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Asserções sobre o TVEM (SPEC §4.3 / §10). Regra de ouro: todo teste que valida
 * "o Trace2Local viu X" DEVE ter o gêmeo negativo "o Trace2Local declarou que não
 * viu Y" — por isso cada asserção positiva tem uma negativa correspondente.
 */
public final class Trace2LocalAssertions {

    private Trace2LocalAssertions() {}

    public static Tracer tracer() {
        return io.opentelemetry.api.GlobalOpenTelemetry.get()
                .getTracer("tech.neural7.trace2local:testing");
    }

    /** Span raiz de teste, com trigger=TEST — para execuções controladas pelo próprio teste. */
    public static Span startTestSpan(String name) {
        return tracer().spanBuilder(name)
                .setAttribute(tech.neural7.trace2local.otel.Trace2LocalAttributes.TRIGGER,
                        tech.neural7.trace2local.otel.Trace2LocalAttributes.TRIGGER_TEST)
                .startSpan();
    }

    /** Espera a execução mais recente concluir e devolve a asserção. */
    public static ExecutionAssert awaitLatestExecution(Duration timeout) {
        return new ExecutionAssert(awaitLatest(timeout), null);
    }

    public static ExecutionAssert awaitExecution(String executionId, Duration timeout) {
        return new ExecutionAssert(awaitById(executionId, timeout), null);
    }

    public static Execution awaitLatest(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long started = System.nanoTime();
        long minWindowNanos = 300L * 1_000_000L; // janela mínima: deixa o assembler processar
        while (System.nanoTime() < deadline) {
            sleep(25);
            boolean waitedEnough = (System.nanoTime() - started) >= minWindowNanos;
            boolean nothingInFlight = Trace2LocalRuntime.pipeline().store().liveExecutions() == 0;
            if (!waitedEnough || !nothingInFlight) {
                continue;
            }
            var recent = Trace2LocalRuntime.pipeline().store().recent(50);
            if (!recent.isEmpty()) {
                var execution = Trace2LocalRuntime.pipeline().store().get(recent.get(0).executionId());
                if (execution.isPresent()) {
                    return execution.get();
                }
            }
        }
        throw new AssertionError("nenhuma execução concluiu em " + timeout);
    }

    private static Execution awaitById(String executionId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var execution = Trace2LocalRuntime.pipeline().store().get(executionId);
            if (execution.isPresent()) {
                return execution.get();
            }
            sleep(20);
        }
        throw new AssertionError("execução " + executionId + " não concluiu em " + timeout);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- asserts

    public static final class ExecutionAssert extends AbstractAssert<ExecutionAssert, Execution> {

        private final String parentPath;

        ExecutionAssert(Execution actual, String parentPath) {
            super(actual, ExecutionAssert.class);
            this.parentPath = parentPath;
        }

        public ExecutionAssert hasStatus(tech.neural7.trace2local.model.ExecutionStatus status) {
            Assertions.assertThat(actual.status()).as(path("status")).isEqualTo(status);
            return this;
        }

        public ExecutionAssert hasTrigger(Trigger trigger) {
            Assertions.assertThat(actual.trigger()).as(path("trigger")).isEqualTo(trigger);
            return this;
        }

        public NodeAssert hasNode(NodeKind kind, String labelContains) {
            Node found = findNode(actual.roots(), kind, labelContains);
            Assertions.assertThat(found)
                    .as(path("nó %s com rótulo contendo '%s'", kind, labelContains))
                    .isNotNull();
            return new NodeAssert(found, path("nó " + kind + " '" + labelContains + "'"));
        }

        public NodeAssert hasRoot(String labelContains) {
            Node found = actual.roots().stream()
                    .filter(n -> n.label() != null && n.label().contains(labelContains))
                    .findFirst().orElse(null);
            Assertions.assertThat(found)
                    .as(path("raiz com rótulo contendo '%s'", labelContains))
                    .isNotNull();
            return new NodeAssert(found, path("raiz '" + labelContains + "'"));
        }

        /** Gêmeo negativo (§10): declara o que NÃO foi visto. */
        public ExecutionAssert doesNotHaveNode(NodeKind kind, String labelContains) {
            Node found = findNode(actual.roots(), kind, labelContains);
            Assertions.assertThat(found)
                    .as(path("nó %s com rótulo contendo '%s' NÃO deve existir", kind, labelContains))
                    .isNull();
            return this;
        }

        public ExecutionAssert hasNodeCount(int count) {
            Assertions.assertThat(countNodes(actual.roots())).as(path("contagem de nós")).isEqualTo(count);
            return this;
        }

        private String path(String fmt, Object... args) {
            String local = String.format(fmt, args);
            return parentPath == null ? local : parentPath + " → " + local;
        }
    }

    public static final class NodeAssert extends AbstractAssert<NodeAssert, Node> {

        private final String path;

        NodeAssert(Node actual, String path) {
            super(actual, NodeAssert.class);
            this.path = path;
        }

        public NodeAssert hasLabel(String expected) {
            Assertions.assertThat(actual.label()).as(path("label")).isEqualTo(expected);
            return this;
        }

        public NodeAssert hasKind(NodeKind kind) {
            Assertions.assertThat(actual.kind()).as(path("kind")).isEqualTo(kind);
            return this;
        }

        public NodeAssert hasStatus(NodeStatus status) {
            Assertions.assertThat(actual.status()).as(path("status")).isEqualTo(status);
            return this;
        }

        public NodeAssert hasAttribute(String key, String value) {
            Assertions.assertThat(actual.attributes()).as(path("attributes")).containsEntry(key, value);
            return this;
        }

        public NodeAssert hasMutation(MutationFidelity fidelity) {
            Assertions.assertThat(actual.mutation()).as(path("mutation")).isNotNull();
            Assertions.assertThat(actual.mutation().fidelity()).as(path("mutation.fidelity")).isEqualTo(fidelity);
            return this;
        }

        public NodeAssert hasMutationOn(String target) {
            Assertions.assertThat(actual.mutation()).as(path("mutation")).isNotNull();
            Assertions.assertThat(actual.mutation().target()).as(path("mutation.target")).isEqualTo(target);
            return this;
        }

        public NodeAssert child(NodeKind kind, String labelContains) {
            Node found = findNode(actual.children(), kind, labelContains);
            Assertions.assertThat(found)
                    .as(path("filho %s contendo '%s'", kind, labelContains))
                    .isNotNull();
            return new NodeAssert(found, path + " → filho '" + labelContains + "'");
        }

        public NodeAssert hasNoChild(NodeKind kind, String labelContains) {
            Node found = findNode(actual.children(), kind, labelContains);
            Assertions.assertThat(found)
                    .as(path("filho %s contendo '%s' NÃO deve existir", kind, labelContains))
                    .isNull();
            return this;
        }

        /** Gêmeo negativo na subárvore deste nó (§10). */
        public NodeAssert doesNotHaveNode(NodeKind kind, String labelContains) {
            Node found = findNode(actual.children(), kind, labelContains);
            Assertions.assertThat(found)
                    .as(path("nó %s contendo '%s' NÃO deve existir na subárvore", kind, labelContains))
                    .isNull();
            return this;
        }

        private String path(String fmt, Object... args) {
            return path + " → " + String.format(fmt, args);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Node findNode(List<Node> nodes, NodeKind kind, String labelContains) {
        for (Node node : nodes) {
            if (node.kind() == kind
                    && (labelContains == null || (node.label() != null && node.label().contains(labelContains)))) {
                return node;
            }
            Node inChild = findNode(node.children(), kind, labelContains);
            if (inChild != null) {
                return inChild;
            }
        }
        return null;
    }

    private static int countNodes(List<Node> nodes) {
        int count = nodes.size();
        for (Node node : nodes) {
            count += countNodes(node.children());
        }
        return count;
    }
}
