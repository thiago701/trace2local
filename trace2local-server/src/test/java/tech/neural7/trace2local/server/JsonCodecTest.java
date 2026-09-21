package tech.neural7.trace2local.server;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonCodecTest {

    @Test
    void durationSerializesAsMillisAndInstantAsIso8601() throws Exception {
        var node = JsonCodec.MAPPER.createObjectNode();
        node.putPOJO("d", Duration.ofMillis(42));
        node.putPOJO("at", Instant.parse("2026-09-18T10:00:00Z"));
        String json = JsonCodec.MAPPER.writeValueAsString(node);
        assertThat(json).contains("\"d\":42");
        assertThat(json).contains("2026-09-18T10:00:00Z");
    }

    @Test
    void serializesTvemRecords() throws Exception {
        var execution = new tech.neural7.trace2local.model.Execution("TV-1", "trace", 
                tech.neural7.trace2local.model.ExecutionStatus.COMPLETED,
                tech.neural7.trace2local.model.Trigger.UI_DISPATCH,
                Instant.parse("2026-09-18T10:00:00Z"), Duration.ofMillis(42),
                java.util.List.of(), tech.neural7.trace2local.model.ExecutionMetrics.EMPTY, java.util.List.of());
        String json = JsonCodec.MAPPER.writeValueAsString(execution);
        assertThat(json).contains("\"executionId\":\"TV-1\"");
        assertThat(json).contains("\"duration\":42");
    }
}
