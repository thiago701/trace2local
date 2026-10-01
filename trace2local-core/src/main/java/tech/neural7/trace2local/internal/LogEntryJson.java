package tech.neural7.trace2local.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.model.LogEntry;

import java.time.Instant;

/**
 * Codec explícito de {@link LogEntry} para o fio (ingest {@code /t2lingest/v1/logs}):
 * sem depender de módulo {@code jsr310} no core e tolerante a campos ausentes.
 */
public final class LogEntryJson {

    private static final int MAX_FIELD = 256;

    private LogEntryJson() {}

    public static ObjectNode toJson(LogEntry e) {
        ObjectNode n = JsonSupport.MAPPER.createObjectNode();
        n.put("timestamp", e.timestamp() != null ? e.timestamp().toString() : null);
        n.put("level", e.level());
        n.put("logger", e.logger());
        n.put("message", e.message());
        n.put("traceId", e.traceId());
        n.put("spanId", e.spanId());
        n.put("requestId", e.requestId());
        n.put("logGroup", e.logGroup());
        n.put("logStream", e.logStream());
        n.put("source", e.source() != null ? e.source().name() : null);
        return n;
    }

    /** Lê uma linha do fio; devolve {@code null} se ilegível (o lote continua). */
    public static LogEntry fromJson(JsonNode n) {
        if (n == null || !n.isObject() || !n.hasNonNull("message")) {
            return null;
        }
        Instant ts;
        try {
            ts = n.hasNonNull("timestamp") ? Instant.parse(n.path("timestamp").asText()) : Instant.now();
        } catch (RuntimeException e) {
            ts = Instant.now();
        }
        LogEntry.LogSource source;
        try {
            source = n.hasNonNull("source") ? LogEntry.LogSource.valueOf(n.path("source").asText()) : LogEntry.LogSource.APP;
        } catch (IllegalArgumentException e) {
            source = LogEntry.LogSource.APP;
        }
        return new LogEntry(ts, field(n, "level"), field(n, "logger"), n.path("message").asText(),
                hex(field(n, "traceId"), 32), hex(field(n, "spanId"), 16), field(n, "requestId"),
                field(n, "logGroup"), field(n, "logStream"), source);
    }

    private static String field(JsonNode n, String name) {
        if (!n.hasNonNull(name)) {
            return null;
        }
        String v = n.path(name).asText();
        return v.length() > MAX_FIELD ? v.substring(0, MAX_FIELD) : v;
    }

    /** Ids de trace/span só passam se forem hex do tamanho certo (entrada externa). */
    private static String hex(String v, int len) {
        if (v == null || v.length() != len) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            if (Character.digit(v.charAt(i), 16) < 0) {
                return null;
            }
        }
        return v.toLowerCase(java.util.Locale.ROOT);
    }
}
