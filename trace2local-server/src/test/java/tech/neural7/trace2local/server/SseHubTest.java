package tech.neural7.trace2local.server;

import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.internal.ExecutionStore;
import tech.neural7.trace2local.internal.LiveEvent;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.ExecutionSummary;
import tech.neural7.trace2local.model.MutationFidelity;
import tech.neural7.trace2local.model.MutationKind;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.NodeStatus;
import tech.neural7.trace2local.model.Trigger;
import tech.neural7.trace2local.model.Warning;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SseHubTest {

    private static final Execution NODE_UPSERT_SAMPLE = sampleExecution();

    @Test
    void executionCompletedCarriesFlatFieldsPerSpec52() {
        SseHub hub = new SseHub(new StubStore(List.of()), 50, 60_000);
        hub.start();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            hub.subscribe(out, () -> {});
            hub.accept(new LiveEvent.ExecutionCompleted("TV-00002", sampleExecution()));
            sleep(250);
            String payload = out.toString(StandardCharsets.UTF_8);
            assertThat(payload).contains("event: execution.completed");
            // §5.2: campos FLAT + duration ISO-8601 (PT..S) + metrics + payload aninhado compatível
            assertThat(payload).contains("\"executionId\":\"TV-00002\"");
            assertThat(payload).contains("\"status\":\"COMPLETED\"");
            assertThat(payload).contains("\"duration\":\"PT");
            assertThat(payload).contains("\"metrics\":{");
            assertThat(payload).contains("\"execution\":{");
        } finally {
            hub.close();
        }
    }

    @Test
    void coalescesManyUpsertsOfSameNodeIntoSingleFrame() throws Exception {
        SseHub hub = new SseHub(new StubStore(List.of(NODE_UPSERT_SAMPLE)), 60, 60_000);
        hub.start();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            hub.subscribe(out, () -> {});

            Node node = NODE_UPSERT_SAMPLE.roots().get(0);
            for (int i = 0; i < 100; i++) {
                hub.accept(new LiveEvent.NodeUpserted("TV-00001", node));
            }
            Thread.sleep(250);
            // 100 upserts do mesmo nó ⇒ no máximo alguns frames (coalescência 20 frames/s)
            String payload = out.toString(StandardCharsets.UTF_8);
            int frames = payload.split("event: node.upserted").length - 1;
            assertThat(frames).isBetween(1, 6);
        } finally {
            hub.close();
        }
    }

    @Test
    void sendsSnapshotOnSubscribe() {
        SseHub hub = new SseHub(new StubStore(List.of(NODE_UPSERT_SAMPLE)), 50, 60_000);
        hub.start();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            hub.subscribe(out, () -> {});
            sleep(150);
            String payload = out.toString(StandardCharsets.UTF_8);
            assertThat(payload).contains("event: execution.snapshot");
            assertThat(payload).contains("TV-00001");
        } finally {
            hub.close();
        }
    }

    @Test
    void sendsHeartbeatWhenIdle() {
        SseHub hub = new SseHub(new StubStore(List.of()), 30, 120);
        hub.start();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            hub.subscribe(out, () -> {});
            sleep(400);
            String payload = out.toString(StandardCharsets.UTF_8);
            assertThat(payload).contains(": ping");
        } finally {
            hub.close();
        }
    }

    @Test
    void notifiesDisconnectOnBrokenStream() throws InterruptedException {
        SseHub hub = new SseHub(new StubStore(List.of(NODE_UPSERT_SAMPLE)), 30, 60_000);
        hub.start();
        try {
            CountDownLatch disconnected = new CountDownLatch(1);
            // snapshot inicial já falha na escrita ⇒ desconexão notificada
            hub.subscribe(new ThrowingOutputStream(), disconnected::countDown);
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(hub.connectedClients()).isZero();
        } finally {
            hub.close();
        }
    }

    @Test
    void systemWarningFramesCarryCountAndMessage() {
        SseHub hub = new SseHub(new StubStore(List.of()), 30, 60_000);
        hub.start();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            hub.subscribe(out, () -> {});
            hub.accept(new LiveEvent.SystemWarning(new Warning(Warning.WarningKind.EVENTS_DROPPED, 137,
                    "Buffer cheio; a árvore pode estar incompleta", Instant.now())));
            sleep(150);
            String payload = out.toString(StandardCharsets.UTF_8);
            assertThat(payload).contains("event: system.warning");
            assertThat(payload).contains("137");
        } finally {
            hub.close();
        }
    }

    // ---------------------------------------------------------------- stubs

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Execution sampleExecution() {
        Node node = new Node("abc123", null, NodeKind.HTTP_SERVER, "POST /orders", NodeStatus.OK,
                Instant.now(), Duration.ofMillis(10), Duration.ofMillis(42),
                Map.of("custom.key", "POST"),
                null,
                new DataMutation(MutationKind.CREATE, "orders", "ORDER#1", null, null, List.of(), MutationFidelity.EXACT),
                null, List.of());
        return new Execution("TV-00001", "4bf92f3577b34da6a3ce929d0e0e4736", ExecutionStatus.COMPLETED,
                Trigger.UI_DISPATCH, Instant.now(), Duration.ofMillis(42), List.of(node),
                new tech.neural7.trace2local.model.ExecutionMetrics(1, 1, 0, 0), List.of());
    }

    private static final class StubStore implements ExecutionStore {
        private final List<Execution> executions;

        StubStore(List<Execution> executions) {
            this.executions = executions;
        }

        @Override
        public List<ExecutionSummary> recent(int limit) {
            return executions.stream().limit(limit).map(e -> new ExecutionSummary(
                    e.executionId(), e.traceId(), e.status(), e.trigger(),
                    e.startedAt(), e.duration(), "root", e.metrics().nodeCount())).toList();
        }

        @Override
        public Optional<Execution> get(String executionId) {
            return executions.stream().filter(e -> e.executionId().equals(executionId)).findFirst();
        }

        @Override
        public void clear() {}

        @Override
        public long droppedEvents() { return 0; }

        @Override
        public long internalErrors() { return 0; }

        @Override
        public int bufferSize() { return 0; }

        @Override
        public int liveExecutions() { return 0; }
    }

    private static final class ThrowingOutputStream extends java.io.OutputStream {
        @Override
        public void write(int b) throws java.io.IOException {
            throw new java.io.IOException("desconectado");
        }
    }
}
