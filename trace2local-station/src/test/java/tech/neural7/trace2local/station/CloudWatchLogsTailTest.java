package tech.neural7.trace2local.station;

import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.internal.LogStore;
import tech.neural7.trace2local.model.LogEntry;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CloudWatch (LocalStack): linhas entre START/END de um stream pertencem ao RequestId (ADR-012). */
class CloudWatchLogsTailTest {

    @Test
    void assignsRequestIdToFunctionStdoutBetweenStartAndEnd() {
        LogStore store = new LogStore();
        CloudWatchLogsTail tail = new CloudWatchLogsTail("http://localhost:4566", "us-east-1", "/aws/lambda/", 1000, store);
        String g = "/aws/lambda/order-processor";
        String s = "2026/09/30/[$LATEST]abc";
        long t = 1_700_000_000_000L;
        tail.ingest(g, s, t, "START RequestId: 9f1c2d3e-0000-4000-8000-000000000001 Version: $LATEST\n");
        tail.ingest(g, s, t + 5, "2026-09-30 INFO  OrderProcessor - pedido ORDER-1 gravado\n");
        tail.ingest(g, s, t + 9, "ERROR falha ao publicar\n");
        tail.ingest(g, s, t + 12, "END RequestId: 9f1c2d3e-0000-4000-8000-000000000001\n");
        tail.ingest(g, s, t + 13, "REPORT RequestId: 9f1c2d3e-0000-4000-8000-000000000001\tDuration: 12.31 ms\tBilled Duration: 13 ms\tMemory Size: 512 MB\tMax Memory Used: 98 MB\tInit Duration: 431.20 ms\n");
        tail.ingest(g, s, t + 20, "linha fora de invocação\n");

        List<LogEntry> lines = store.forExecution(null, Set.of("9f1c2d3e-0000-4000-8000-000000000001"));
        assertThat(lines).hasSize(5);
        assertThat(lines).allMatch(l -> l.source() == LogEntry.LogSource.CLOUDWATCH && g.equals(l.logGroup()));
        assertThat(lines.get(1).level()).isEqualTo("INFO");
        assertThat(lines.get(2).level()).isEqualTo("ERROR");
        assertThat(lines.get(4).level()).isEqualTo("PLATFORM");
        assertThat(store.uncorrelated(10)).extracting(LogEntry::message).containsExactly("linha fora de invocação");
    }

    /** Caso real (LocalStack 4.2): a exceção do runtime chega como um evento por linha e sem nível. */
    @Test
    void foldsRuntimeStackTraceIntoOneErrorEvent() {
        LogStore store = new LogStore();
        CloudWatchLogsTail tail = new CloudWatchLogsTail("http://localhost:4566", "us-east-1", "/aws/lambda/", 1000, store);
        String g = "/aws/lambda/order-processor";
        String s = "2026/10/01/[$LATEST]cc8f";
        String rid = "b97dfe04-963d-4375-9cee-c0b02dc6c682";
        long t = 1_700_000_000_000L;
        tail.ingestFolded(g, List.of(
                new String[] {s, Long.toString(t), "START RequestId: " + rid + " Version: $LATEST"},
                new String[] {s, Long.toString(t + 1), "ordem recusada: ORDER-C3 excede o limite de crédito: java.lang.IllegalStateException"},
                new String[] {s, Long.toString(t + 2), "java.lang.IllegalStateException: ordem recusada: ORDER-C3 excede o limite de crédito"},
                new String[] {s, Long.toString(t + 3), "\tat tech.neural7.trace2local.examples.lambda.OrderProcessor.handle(OrderProcessor.java:87)"},
                new String[] {s, Long.toString(t + 4), "\tat java.base/java.lang.reflect.Method.invoke(Unknown Source)"},
                new String[] {s, Long.toString(t + 5), "END RequestId: " + rid}));

        List<LogEntry> lines = store.forExecution(null, Set.of(rid));
        assertThat(lines).extracting(LogEntry::level).containsExactly("PLATFORM", "ERROR", "ERROR", "PLATFORM");
        assertThat(lines.get(2).message()).startsWith("java.lang.IllegalStateException").contains("\n\tat tech.neural7")
                .contains("Method.invoke");
        assertThat(CloudWatchLogsTail.detectLevel("INFO pedido ORDER-C1 gravado na tabela orders")).isEqualTo("INFO");
        assertThat(CloudWatchLogsTail.detectLevel("pedido gravado: status ok")).isEqualTo("INFO");
    }
}
