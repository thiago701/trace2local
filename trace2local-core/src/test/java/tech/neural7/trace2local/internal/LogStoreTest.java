package tech.neural7.trace2local.internal;

import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.model.LogEntry;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Logs correlacionados (ADR-012): índices trace/RequestId, dedupe entre fontes e redação de texto livre. */
class LogStoreTest {

    private static LogEntry line(String msg, String trace, String span, String req, LogEntry.LogSource src, long ms) {
        return new LogEntry(Instant.ofEpochMilli(1_700_000_000_000L + ms), "INFO", "app", msg, trace, span, req,
                "/aws/lambda/fn", "2026/09/30/[$LATEST]x", src);
    }

    @Test
    void unitesTraceAndRequestIndexesAndPrefersRealCloudWatchPlatformLines() {
        LogStore store = new LogStore();
        String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
        store.append(line("START RequestId: r-1 Version: $LATEST", trace, null, "r-1", LogEntry.LogSource.PLATFORM, 0));
        store.append(line("pedido gravado", trace, "aaaaaaaaaaaaaaaa", "r-1", LogEntry.LogSource.APP, 5));
        // CloudWatch real (LocalStack) entrega START e a mesma linha de app, só com RequestId
        store.append(line("START RequestId: r-1 Version: $LATEST", null, null, "r-1", LogEntry.LogSource.CLOUDWATCH, 1));
        store.append(line("pedido gravado", null, null, "r-1", LogEntry.LogSource.CLOUDWATCH, 6));
        store.append(line("pedido gravado", null, null, "r-1", LogEntry.LogSource.CLOUDWATCH, 9)); // repetição legítima

        List<LogEntry> lines = store.forExecution(trace, Set.of("r-1"));
        assertThat(lines).extracting(LogEntry::message)
                .containsExactly("START RequestId: r-1 Version: $LATEST", "pedido gravado", "pedido gravado");
        // START sintetizado saiu (o real chegou); a linha de app que ficou é a que tem span
        assertThat(lines.get(0).source()).isEqualTo(LogEntry.LogSource.CLOUDWATCH);
        assertThat(lines.get(1).spanId()).isEqualTo("aaaaaaaaaaaaaaaa");
    }

    @Test
    void redactsSecretsInsideFreeText() {
        String r = TextRedactor.redact("user=maria@x.com.br token=abc123 Authorization: Bearer eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl "
                + "url=https://admin:hunter2@db.local/x cpf 123.456.789-09 epoch 1727740800000 cartao 4111 1111 1111 1111");
        assertThat(r).doesNotContain("maria@x.com.br").doesNotContain("abc123").doesNotContain("hunter2")
                .doesNotContain("123.456.789-09").doesNotContain("4111 1111 1111 1111").doesNotContain("eyJhbGciOi");
        assertThat(r).contains("1727740800000"); // epoch-millis NÃO é cartão
        assertThat(r).contains("[TRACE2LOCAL_REDACTED]");
    }

    @Test
    void boundedAndCountsDrops() {
        LogStore store = new LogStore(2, 3);
        for (int i = 0; i < 5; i++) {
            store.append(line("l" + i, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null, null, LogEntry.LogSource.APP, i));
        }
        assertThat(store.forExecution("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Set.of())).hasSize(3);
        assertThat(store.dropped()).isEqualTo(2);
    }
}
