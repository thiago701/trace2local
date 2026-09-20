package tech.neural7.tracevanta.internal;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import tech.neural7.tracevanta.model.Execution;
import tech.neural7.tracevanta.model.ExecutionStatus;
import tech.neural7.tracevanta.model.Node;
import tech.neural7.tracevanta.model.NodeKind;
import tech.neural7.tracevanta.model.Warning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariantes I1–I2 do TVEM sob eventos fora de ordem e perdidos (SPEC §10 —
 * teste de propriedade, 0 contraexemplo).
 */
class TraceAssemblerPropertyTest {

    /** Floresta aleatória: pais sempre entre os nós anteriores ou nulo. */
    @Provide
    Arbitrary<List<int[]>> randomForest() {
        return Arbitraries.integers().between(1, 12).map(n -> {
            List<int[]> nodes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int parent = i == 0 ? -1 : ThreadLocalRandom.current().nextInt(-1, i);
                nodes.add(new int[]{i, parent});
            }
            return nodes;
        });
    }

    @Property(tries = 150)
    void fullTreeSurvivesOutOfOrderArrival(@ForAll("randomForest") List<int[]> forest) {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fast())) {
            String traceId = "prop-" + ThreadLocalRandom.current().nextLong();
            Instant t0 = Instant.parse("2026-09-18T12:00:00Z");

            List<int[]> starts = new ArrayList<>(forest);
            List<int[]> ends = new ArrayList<>(forest);
            Collections.shuffle(starts, ThreadLocalRandom.current());
            Collections.shuffle(ends, ThreadLocalRandom.current());

            for (int[] n : starts) {
                offerStart(tp, traceId, "s" + n[0], n[1], t0.plusMillis(n[0]));
            }
            for (int[] n : ends) {
                offerEnd(tp, traceId, "s" + n[0], t0.plusMillis(n[0] + 5));
            }
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(10));

            // buffer folgado (4096) ⇒ nada foi descartado ⇒ I1: todos os nós, exatamente uma vez
            assertThat(tp.pipeline().buffer().dropped()).isZero();
            Set<String> ids = allNodeIds(execution);
            assertThat(ids).hasSize(forest.size());
            for (int[] n : forest) {
                assertThat(ids).contains("s" + n[0]);
            }
            assertAllSelfTimesNonNegative(execution.roots());
            assertThat(execution.status()).isNotEqualTo(ExecutionStatus.RUNNING);
        }
    }

    @Property(tries = 150)
    void dropsAreDeclaredAndObservedNodesNeverDisappear(@ForAll("randomForest") List<int[]> forest) {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fastDrop())) {
            String traceId = "prop-" + ThreadLocalRandom.current().nextLong();
            Instant t0 = Instant.parse("2026-09-18T12:00:00Z");

            List<int[]> starts = new ArrayList<>(forest);
            List<int[]> ends = new ArrayList<>(forest);
            Collections.shuffle(starts, ThreadLocalRandom.current());
            Collections.shuffle(ends, ThreadLocalRandom.current());

            Set<String> accepted = new HashSet<>();
            for (int[] n : starts) {
                if (offerStart(tp, traceId, "s" + n[0], n[1], t0.plusMillis(n[0]))) {
                    accepted.add("s" + n[0]);
                }
            }
            for (int[] n : ends) {
                if (offerEnd(tp, traceId, "s" + n[0], t0.plusMillis(n[0] + 5))) {
                    accepted.add("s" + n[0]);
                }
            }
            tp.start();

            Execution execution = tp.awaitExecutionByTrace(traceId, Duration.ofSeconds(10));

            // I1 — tudo o que o assembler VIU está na árvore; nada observado some
            assertThat(allNodeIds(execution)).containsExactlyInAnyOrderElementsOf(accepted);

            // honestidade (I3 aplicada ao buffer): todo descarte é declarado, sempre
            if (tp.pipeline().buffer().dropped() > 0) {
                assertThat(tp.events()).anyMatch(e -> e instanceof LiveEvent.SystemWarning w
                        && w.warning().kind() == Warning.WarningKind.EVENTS_DROPPED);
            }
            assertAllSelfTimesNonNegative(execution.roots());
        }
    }

    @Property(tries = 50)
    void systemWarningReachesListenersUnderBurst(@ForAll @IntRange(min = 1, max = 32) int burstSize) {
        try (TestPipeline tp = new TestPipeline(TestPipelineConfig.fastWithBuffer(4))) {
            Instant t0 = Instant.now();
            for (int i = 0; i < burstSize + 4; i++) {
                tp.pipeline().buffer().offer(new SpanStartEvent("prop-drop-" + i, "s" + i, null, null, null,
                        NodeKind.UNKNOWN, "n", java.util.Map.of(), t0));
            }
            assertThat(tp.pipeline().buffer().dropped()).isGreaterThanOrEqualTo(burstSize);
            tp.start();
            Thread.sleep(700);
            assertThat(tp.events()).anyMatch(e -> e instanceof LiveEvent.SystemWarning w
                    && w.warning().kind() == Warning.WarningKind.EVENTS_DROPPED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean offerStart(TestPipeline tp, String traceId, String spanId, int parent, Instant at) {
        return tp.pipeline().buffer().offer(new SpanStartEvent(traceId, spanId,
                parent >= 0 ? "s" + parent : null, null, null, NodeKind.BUSINESS, spanId, java.util.Map.of(), at));
    }

    private static boolean offerEnd(TestPipeline tp, String traceId, String spanId, Instant at) {
        return tp.pipeline().buffer().offer(new SpanEndEvent(traceId, spanId, null, null, null,
                NodeKind.UNKNOWN, spanId, java.util.Map.of(), at.minusMillis(5), at, false, null, null, null, List.of(), false));
    }

    private static Set<String> allNodeIds(Execution execution) {
        Set<String> ids = new HashSet<>();
        collect(execution.roots(), ids);
        return ids;
    }

    private static void collect(List<Node> nodes, Set<String> out) {
        for (Node node : nodes) {
            out.add(node.nodeId());
            collect(node.children(), out);
        }
    }

    private static void assertAllSelfTimesNonNegative(List<Node> nodes) {
        for (Node node : nodes) {
            assertThat(node.selfTime()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
            assertAllSelfTimesNonNegative(node.children());
        }
    }
}
