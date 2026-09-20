package tech.neural7.tracevanta.internal;

import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.model.DataMutation;
import tech.neural7.tracevanta.model.ErrorInfo;
import tech.neural7.tracevanta.model.Execution;
import tech.neural7.tracevanta.model.ExecutionStatus;
import tech.neural7.tracevanta.model.MutationFidelity;
import tech.neural7.tracevanta.model.MutationKind;
import tech.neural7.tracevanta.model.Node;
import tech.neural7.tracevanta.model.NodeKind;
import tech.neural7.tracevanta.model.NodeStatus;
import tech.neural7.tracevanta.model.Warning;
import tech.neural7.tracevanta.spi.MutationEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAssemblerTest {

    private static final Instant T0 = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void buildsTreeAndComputesSelfTime() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-1";
            start(tp, traceId, "root", null, NodeKind.HTTP_SERVER, T0, "exec-1");
            start(tp, traceId, "child", "root", NodeKind.DYNAMODB, T0.plusMillis(5), null);
            start(tp, traceId, "grand", "child", NodeKind.BUSINESS, T0.plusMillis(8), null);
            end(tp, traceId, "grand", T0.plusMillis(20), null);
            end(tp, traceId, "child", T0.plusMillis(30), null);
            end(tp, traceId, "root", T0.plusMillis(60), null);
            tp.start();

            Execution execution = tp.awaitExecution("exec-1", Duration.ofSeconds(5));

            assertThat(execution.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(execution.metrics().nodeCount()).isEqualTo(3);
            assertThat(execution.metrics().maxDepth()).isEqualTo(3);
            assertThat(execution.roots()).hasSize(1);

            Node root = execution.roots().get(0);
            assertThat(root.totalTime()).isEqualTo(Duration.ofMillis(60));
            assertThat(root.children()).hasSize(1);
            Node child = root.children().get(0);
            assertThat(child.totalTime()).isEqualTo(Duration.ofMillis(25));
            assertThat(child.children().get(0).totalTime()).isEqualTo(Duration.ofMillis(12));
            // I2 — selfTime nunca negativo: child.self = 25 - 12
            assertThat(child.selfTime()).isEqualTo(Duration.ofMillis(13));
            assertThat(root.selfTime()).isEqualTo(Duration.ofMillis(35));
            assertThat(child.selfTime()).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(root.selfTime()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    @Test
    void reparentsOrphansAtTheRootWithHonestWarnings() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-2";
            // filho cujo pai nunca chega (pai descartado na borda)
            start(tp, traceId, "orphan", "lost-parent", NodeKind.BUSINESS, T0, null);
            end(tp, traceId, "orphan", T0.plusMillis(10), null);
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(5));

            assertThat(execution.status()).isEqualTo(ExecutionStatus.PARTIAL); // aviso de órfão ⇒ PARTIAL
            assertThat(execution.roots()).hasSize(1);
            Node orphan = execution.roots().get(0);
            assertThat(orphan.status()).isEqualTo(NodeStatus.ORPHANED);
            assertThat(orphan.parentId()).isNull();
            assertThat(execution.warnings())
                    .anyMatch(w -> w.kind() == Warning.WarningKind.ORPHANED_NODES)
                    .anyMatch(w -> w.kind() == Warning.WarningKind.CONTEXT_LOST);
        }
    }

    @Test
    void marksSnsProducerWithoutConsumerAsOrphanedAwaitingConsumption() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-3";
            start(tp, traceId, "root", null, NodeKind.HTTP_SERVER, T0, null);
            start(tp, traceId, "sns", "root", NodeKind.SNS, T0.plusMillis(5), null);
            end(tp, traceId, "sns", T0.plusMillis(15), null);
            end(tp, traceId, "root", T0.plusMillis(30), null);
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(5));

            Node sns = execution.roots().get(0).children().get(0);
            assertThat(sns.kind()).isEqualTo(NodeKind.SNS);
            assertThat(sns.status()).isEqualTo(NodeStatus.ORPHANED);
            assertThat(execution.warnings())
                    .anyMatch(w -> w.message().contains("aguardando consumo"));
        }
    }

    @Test
    void correlatesProducerAndConsumerThroughLinks() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-4";
            start(tp, traceId, "root", null, NodeKind.HTTP_SERVER, T0, null);
            start(tp, traceId, "sns", "root", NodeKind.SNS, T0.plusMillis(5), null);
            end(tp, traceId, "sns", T0.plusMillis(15), null);
            // consumidor chega como raiz (processo/thread sem contexto) com Link para o produtor
            start(tp, traceId, "sqs-consumer", null, NodeKind.SQS, T0.plusMillis(20), null);
            end(tp, traceId, "sqs-consumer", T0.plusMillis(40), null, List.of("sns"));
            end(tp, traceId, "root", T0.plusMillis(50), null);
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(5));

            Node sns = execution.roots().get(0).children().get(0);
            assertThat(sns.status()).isEqualTo(NodeStatus.OK); // consumidor chegou
            assertThat(sns.children()).hasSize(1);
            assertThat(sns.children().get(0).kind()).isEqualTo(NodeKind.SQS);
        }
    }

    @Test
    void fusesMutationArrivingOutOfOrder() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-5";
            DataMutation mutation = new DataMutation(MutationKind.UPDATE, "orders", "ORDER#1",
                    null, null, List.of(), MutationFidelity.EXACT);
            tp.pipeline().buffer().offer(new MutationEvent("dyn", traceId, mutation, T0.plusMillis(2)));
            start(tp, traceId, "dyn", null, NodeKind.DYNAMODB, T0, null);
            end(tp, traceId, "dyn", T0.plusMillis(10), null);
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(5));

            assertThat(execution.roots().get(0).mutation()).isEqualTo(mutation);
            assertThat(execution.roots().get(0).mutation().fidelity()).isEqualTo(MutationFidelity.EXACT);
        }
    }

    @Test
    void errorNodeFailsTheExecution() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-6";
            start(tp, traceId, "root", null, NodeKind.HTTP_SERVER, T0, null);
            end(tp, traceId, "root", T0.plusMillis(10), "Boom");
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(5));

            assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(execution.roots().get(0).status()).isEqualTo(NodeStatus.ERROR);
            assertThat(execution.roots().get(0).error().message()).contains("Boom");
        }
    }

    @Test
    void spanEndWithoutStartStillBuildsTheNode() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-7";
            // start descartado na borda — o fim ainda monta o nó (I1)
            end(tp, traceId, "lost-start", T0.plusMillis(5), null);
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(5));

            assertThat(execution.roots()).hasSize(1);
            assertThat(execution.roots().get(0).nodeId()).isEqualTo("lost-start");
            assertThat(execution.warnings()).anyMatch(w -> w.message().contains("não foi observado"));
        }
    }

    @Test
    void executionDurationUsesSpanWindowNotEventProcessingOrder() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-duration";
            // modo Lambda: a mutação é capturada DURANTE o span, mas chega por último;
            // medir pela ordem de processamento daria duração NEGATIVA (-255 ms real)
            start(tp, traceId, "root", null, NodeKind.HTTP_SERVER, T0, null);
            end(tp, traceId, "root", T0.plusMillis(120), null);
            tp.pipeline().buffer().offer(new MutationEvent("root", traceId,
                    new DataMutation(MutationKind.CREATE, "orders", "ORDER#1",
                            null, null, List.of(), MutationFidelity.EXACT),
                    T0.plusMillis(5)));
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(5));

            assertThat(execution.duration()).isEqualTo(Duration.ofMillis(120));
            assertThat(execution.duration().isNegative()).isFalse();
        }
    }

    @Test
    void firstExplicitExecutionIdWinsOverLateSpans() {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "trace-exec-id";
            // o consumidor (span tardio) carrega outro tv.execution.id — a
            // identidade da execução é a do primeiro id explícito (span raiz)
            tp.pipeline().buffer().offer(new SpanEndEvent(traceId, "root", null, "exec-A", null,
                    NodeKind.UNKNOWN, "root", Map.of(), T0, T0.plusMillis(10),
                    false, null, null, null, List.of(), false));
            tp.pipeline().buffer().offer(new SpanEndEvent(traceId, "late", "root", "exec-B", null,
                    NodeKind.UNKNOWN, "late", Map.of(), T0.plusMillis(5), T0.plusMillis(15),
                    false, null, null, null, List.of(), false));
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(5));

            assertThat(execution.executionId()).isEqualTo("exec-A");
        }
    }

    @Test
    void retainsOnlyTheLastExecutions() {
        TraceVantaConfig cfg = TestPipelineConfig.fastWithRetention(2);
        try (TestPipeline tp = new TestPipeline(cfg)) {
            for (int i = 0; i < 4; i++) {
                String traceId = "trace-ret-" + i;
                start(tp, traceId, "root", null, NodeKind.HTTP_SERVER, T0.plusSeconds(i), null);
                end(tp, traceId, "root", T0.plusSeconds(i + 1), null);
            }
            tp.start();
            Thread.sleep(700);
            assertThat(tp.pipeline().store().recent(10)).hasSize(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- helpers

    static void start(TestPipeline tp, String traceId, String spanId, String parentId,
                      NodeKind kind, Instant at, String executionId) {
        tp.pipeline().buffer().offer(new SpanStartEvent(traceId, spanId, parentId, executionId, null,
                kind, kind + ":" + spanId, Map.of(), at));
    }

    static void end(TestPipeline tp, String traceId, String spanId, Instant at, String errorMessage) {
        end(tp, traceId, spanId, at, errorMessage, List.of());
    }

    static void end(TestPipeline tp, String traceId, String spanId, Instant at, String errorMessage,
                    List<String> links) {
        tp.pipeline().buffer().offer(new SpanEndEvent(traceId, spanId, null, null, null,
                NodeKind.UNKNOWN, NodeKind.UNKNOWN + ":" + spanId, Map.of(), at.minusMillis(10), at,
                errorMessage != null,
                errorMessage != null ? new ErrorInfo("E", errorMessage, null) : null,
                null, null, links, false));
    }
}
