package tech.neural7.trace2local.config;

/**
 * Configuração pública do Trace2Local (SPEC §5.4). Resolução em cascata
 * (sistema &gt; ambiente &gt; application.yml &gt; padrão) fica a cargo do starter;
 * aqui vive o modelo com os padrões normativos.
 */
public record Trace2LocalConfig(
        boolean enabled,
        String basePath,
        int port,
        String bindAddress,
        boolean allowNonLoopback,
        int bufferCapacity,
        int retentionMaxExecutions,
        int payloadMaxBytes,
        RedactionMode redactionMode,
        boolean dynamoDbCaptureBefore,
        JdbcMutationCapture jdbcMutationCapture,
        String stationEndpoint,
        String stationToken,
        long flushTimeoutMs,
        long quiescenceMs) {

    public static Builder builder() {
        return new Builder();
    }

    public static Trace2LocalConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled = true;
        private String basePath = "/trace2local";
        private int port = 9876;
        private String bindAddress = "127.0.0.1";
        private boolean allowNonLoopback = false;
        private int bufferCapacity = 4096;
        private int retentionMaxExecutions = 100;
        private int payloadMaxBytes = 8192;
        private RedactionMode redactionMode = RedactionMode.STRICT;
        private boolean dynamoDbCaptureBefore = true;
        private JdbcMutationCapture jdbcMutationCapture = JdbcMutationCapture.OFF;
        private String stationEndpoint;
        private String stationToken;
        private long flushTimeoutMs = 200;
        private long quiescenceMs = 5_000;

        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder basePath(String v) { this.basePath = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder bindAddress(String v) { this.bindAddress = v; return this; }
        public Builder allowNonLoopback(boolean v) { this.allowNonLoopback = v; return this; }
        public Builder bufferCapacity(int v) { this.bufferCapacity = v; return this; }
        public Builder retentionMaxExecutions(int v) { this.retentionMaxExecutions = v; return this; }
        public Builder payloadMaxBytes(int v) { this.payloadMaxBytes = v; return this; }
        public Builder redactionMode(RedactionMode v) { this.redactionMode = v; return this; }
        public Builder dynamoDbCaptureBefore(boolean v) { this.dynamoDbCaptureBefore = v; return this; }
        public Builder jdbcMutationCapture(JdbcMutationCapture v) { this.jdbcMutationCapture = v; return this; }
        public Builder stationEndpoint(String v) { this.stationEndpoint = v; return this; }
        /**
         * Token compartilhado do Station (opcional): quando definido, o ingest
         * OTLP e o canal de mutação exigem {@code Authorization: Bearer <token>}.
         * Protege a porta de ingest quando o Station é exposto além do loopback
         * (ADR-007/§8.1). Vazio = sem autenticação (padrão local).
         */
        public Builder stationToken(String v) { this.stationToken = v; return this; }
        public Builder flushTimeoutMs(long v) { this.flushTimeoutMs = v; return this; }
        public Builder quiescenceMs(long v) { this.quiescenceMs = v; return this; }

        public Trace2LocalConfig build() {
            return new Trace2LocalConfig(enabled, basePath, port, bindAddress, allowNonLoopback,
                    bufferCapacity, retentionMaxExecutions, payloadMaxBytes, redactionMode,
                    dynamoDbCaptureBefore, jdbcMutationCapture, stationEndpoint, stationToken,
                    flushTimeoutMs, quiescenceMs);
        }
    }
}
