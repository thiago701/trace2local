package tech.neural7.trace2local.internal;

import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Continuação TARDIA (SPEC §4.11) — descoberta no caso real Lambda + SQS +
 * event source mapping do LocalStack: o consumidor roda segundos depois do
 * produtor (cold start, polling da fila), DEPOIS da janela de quiescência. Antes,
 * nascia uma execução separada "PARTIAL/ORPHANED"; agora é fundida na árvore do
 * produtor (mesmo executionId), independentemente da ordem de chegada dos spans.
 */
class LateContinuationTest {

    private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");

    private static Trace2LocalConfig cfg(long lateMs) {
        return Trace2LocalConfig.builder().quiescenceMs(400).retentionMaxExecutions(50).lateContinuationMs(lateMs).build();
    }

    private static void span(TestPipeline tp, String trace, String id, String parent, String exec, NodeKind kind,
                             String label, long startMs, long endMs) {
        tp.pipeline().buffer().offer(new SpanEndEvent(trace, id, parent, exec, null, kind, label, Map.of(),
                T0.plusMillis(startMs), T0.plusMillis(endMs), false, null, null, null, List.of(), true));
    }

    @Test
    void lateConsumerIsMergedIntoTheProducerTreeEvenWhenItsChildArrivesFirst() throws Exception {
        try (TestPipeline tp = new TestPipeline(cfg(60_000))) {
            tp.start();
            String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
            // produtor (OTLP: só spans fechados) — conclui após a quiescência
            span(tp, trace, "p-root", null, "exec-producer", NodeKind.LAMBDA, "order-processor", 0, 40);
            span(tp, trace, "p-ddb", "p-root", null, NodeKind.DYNAMODB, "DynamoDB: orders", 5, 20);
            span(tp, trace, "p-sqs", "p-root", null, NodeKind.SQS, "SQS: orders-queue", 22, 30);
            Execution first = tp.awaitExecution("exec-producer", Duration.ofSeconds(5));
            assertThat(first.metrics().nodeCount()).isEqualTo(3);

            // consumidor chega DEPOIS — e o filho antes do próprio pai (ordem do lote OTLP)
            Thread.sleep(600);
            span(tp, trace, "c-ddb", "c-root", null, NodeKind.DYNAMODB, "DynamoDB: orders", 2_010, 2_040);
            span(tp, trace, "c-root", "p-sqs", "exec-consumer", NodeKind.LAMBDA, "order-billing", 2_000, 2_050);

            Execution merged = awaitNodeCount(tp, "exec-producer", 5);
            assertThat(merged.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(merged.warnings()).isEmpty();
            Node sqs = find(merged.roots(), "p-sqs");
            assertThat(sqs.children()).extracting(Node::nodeId).containsExactly("c-root");
            assertThat(sqs.children().get(0).children()).extracting(Node::nodeId).containsExactly("c-ddb");
            assertThat(merged.duration()).isEqualTo(Duration.ofMillis(2_050));
            // a execução tardia não sobrevive no acervo e a UI é avisada da fusão
            assertThat(tp.pipeline().store().get("exec-consumer")).isEmpty();
            assertThat(tp.events()).anyMatch(e -> e instanceof LiveEvent.ExecutionMerged m
                    && m.executionId().equals("exec-consumer") && m.intoExecutionId().equals("exec-producer"));
        }
    }

    @Test
    void provisionalIdIsRenamedWhenTheRootArrivesAfterItsChildren() throws Exception {
        // achado do loop de usabilidade (Station + Lambda): o lote OTLP traz os filhos antes da
        // raiz; a execução nascia como TV-xxxxx na UI e virava fantasma "em curso" ao ganhar o
        // id declarado pela raiz. Agora a troca é anunciada (execution.renamed).
        try (TestPipeline tp = new TestPipeline(cfg(60_000))) {
            tp.start();
            String trace = "6bf92f3577b34da6a3ce929d0e0e4738";
            span(tp, trace, "r-ddb", "r-root", null, NodeKind.DYNAMODB, "DynamoDB: pix-transfers", 5, 20);
            span(tp, trace, "r-root", null, "exec-declared", NodeKind.LAMBDA, "pix-api · GET /pix/transfers/{id}", 0, 30);
            Execution e = tp.awaitExecution("exec-declared", Duration.ofSeconds(5));
            assertThat(e.metrics().nodeCount()).isEqualTo(2);
            String provisional = tp.events().stream().filter(x -> x instanceof LiveEvent.ExecutionStarted)
                    .map(x -> ((LiveEvent.ExecutionStarted) x).executionId()).findFirst().orElseThrow();
            assertThat(provisional).isNotEqualTo("exec-declared");
            assertThat(tp.events()).anyMatch(x -> x instanceof LiveEvent.ExecutionRenamed r
                    && r.executionId().equals(provisional) && r.toExecutionId().equals("exec-declared")
                    && r.traceId().equals(trace));
            // depois da troca, todo evento usa o id declarado
            assertThat(tp.events()).filteredOn(x -> x instanceof LiveEvent.ExecutionCompleted)
                    .allMatch(x -> ((LiveEvent.ExecutionCompleted) x).executionId().equals("exec-declared"));
        }
    }

    @Test
    void sameTraceWithoutCausalLinkIsNotMergedByCoincidence() throws Exception {
        try (TestPipeline tp = new TestPipeline(cfg(60_000))) {
            tp.start();
            String trace = "5bf92f3577b34da6a3ce929d0e0e4737";
            span(tp, trace, "a-root", null, "exec-a", NodeKind.HTTP_SERVER, "POST /orders", 0, 10);
            tp.awaitExecution("exec-a", Duration.ofSeconds(5));
            Thread.sleep(500);
            // traceparent reaproveitado por um cliente externo: pai desconhecido na árvore concluída
            span(tp, trace, "b-root", "external-client-span", "exec-b", NodeKind.HTTP_SERVER, "POST /orders", 1_000, 1_010);
            Execution b = tp.awaitExecution("exec-b", Duration.ofSeconds(5));
            assertThat(b.metrics().nodeCount()).isEqualTo(1);
            assertThat(tp.pipeline().store().get("exec-a").orElseThrow().metrics().nodeCount()).isEqualTo(1);
        }
    }

    @Test
    void entryPointCalledByAnUntracedHopIsALegitimateRootNotAnOrphan() throws Exception {
        try (TestPipeline tp = new TestPipeline(cfg(60_000))) {
            tp.start();
            String trace = "7bf92f3577b34da6a3ce929d0e0e4739";
            // API Gateway (LocalStack/X-Ray) injeta um pai que nunca chega ao Trace2Local
            span(tp, trace, "api-root", "apigw-segment", "exec-api", NodeKind.LAMBDA, "pix-api", 0, 50);
            span(tp, trace, "api-ddb", "api-root", null, NodeKind.DYNAMODB, "DynamoDB: pix-transfers", 5, 10);
            Execution e = tp.awaitExecution("exec-api", Duration.ofSeconds(5));
            assertThat(e.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(e.roots().get(0).status().name()).isEqualTo("OK");
            assertThat(e.roots().get(0).attributes()).containsEntry(TraceAssembler.ENTRY_REMOTE_PARENT, "apigw-segment");
        }
    }

    @Test
    void disabledWindowKeepsTheHonestOrphanBehaviour() throws Exception {
        try (TestPipeline tp = new TestPipeline(cfg(0))) {
            tp.start();
            String trace = "6bf92f3577b34da6a3ce929d0e0e4738";
            span(tp, trace, "p-root", null, "exec-p", NodeKind.LAMBDA, "order-processor", 0, 20);
            span(tp, trace, "p-sqs", "p-root", null, NodeKind.SQS, "SQS: orders-queue", 5, 10);
            tp.awaitExecution("exec-p", Duration.ofSeconds(5));
            Thread.sleep(500);
            span(tp, trace, "c-root", "p-sqs", "exec-c", NodeKind.LAMBDA, "order-billing", 900, 950);
            Execution late = tp.awaitExecution("exec-c", Duration.ofSeconds(5));
            assertThat(late.status()).isEqualTo(ExecutionStatus.PARTIAL);
            assertThat(late.roots().get(0).status().name()).isEqualTo("ORPHANED");
        }
    }

    private static Execution awaitNodeCount(TestPipeline tp, String id, int n) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < deadline) {
            var e = tp.pipeline().store().get(id);
            if (e.isPresent() && e.get().metrics().nodeCount() == n) {
                return e.get();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("execução " + id + " não chegou a " + n + " nós");
    }

    private static Node find(List<Node> nodes, String id) {
        for (Node n : nodes) {
            if (n.nodeId().equals(id)) {
                return n;
            }
            Node c = find(n.children(), id);
            if (c != null) {
                return c;
            }
        }
        return null;
    }
}
