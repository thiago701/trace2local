package tech.neural7.tracevanta.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import tech.neural7.tracevanta.config.JdbcMutationCapture;
import tech.neural7.tracevanta.config.RedactionMode;
import tech.neural7.tracevanta.config.TraceVantaConfig;

/**
 * Propriedades {@code tracevanta.*} (SPEC §5.4). A resolução em cascata
 * sistema &gt; ambiente &gt; application.yml &gt; padrão é a nativa do Spring
 * Environment — aqui só se declara o contrato.
 *
 * <p><b>Grupos aninhados são OBRIGATÓRIOS:</b> no Boot 4.1 o binder não faz o
 * match relaxed de caminho dotted ({@code tracevanta.redaction.mode}) para
 * campo flat ({@code redactionMode}) — sem os grupos, a config da SPEC seria
 * silenciosamente ignorada. O {@link PropertyBindingContractTest} trava o
 * contrato de todos os nomes documentados.
 */
@ConfigurationProperties(prefix = "tracevanta")
public class TraceVantaProperties {

    /** Desliga tudo sem remover a dependência (kill switch — SPEC §7.3). */
    private boolean enabled = true;

    /** Prefixo da UI e da API. */
    private String basePath = "/tracevanta";

    /** Porta do servidor TraceVanta; 0 = efêmera. */
    private int port = 9876;

    /** Bind (SPEC §8.1: loopback obrigatório por padrão). */
    private String bindAddress = "127.0.0.1";

    /** Expor fora do loopback — emite WARN a cada boot. */
    private boolean allowNonLoopback = false;

    /** Teto do flush síncrono em Lambda, ms. */
    private long flushTimeoutMs = 200;

    /** Janela de quiescência do assembler, ms (fluxos só-OTLP e produtor aguardando consumidor). */
    private long quiescenceMs = 3000;

    /** Escape consciente para produção (SPEC §8.4). */
    private boolean iKnowWhatImDoing = false;

    private final Buffer buffer = new Buffer();
    private final Retention retention = new Retention();
    private final Payload payload = new Payload();
    private final Redaction redaction = new Redaction();
    private final Aws aws = new Aws();
    private final Jdbc jdbc = new Jdbc();
    private final Station station = new Station();

    // ------------------------------------------------------------- grupos (nomes dotted da SPEC §5.4)

    public static class Buffer {
        private int capacity = 4096;
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
    }

    public static class Retention {
        private int maxExecutions = 100;
        public int getMaxExecutions() { return maxExecutions; }
        public void setMaxExecutions(int maxExecutions) { this.maxExecutions = maxExecutions; }
    }

    public static class Payload {
        private int maxBytes = 8192;
        public int getMaxBytes() { return maxBytes; }
        public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
    }

    public static class Redaction {
        private RedactionMode mode = RedactionMode.STRICT;
        public RedactionMode getMode() { return mode; }
        public void setMode(RedactionMode mode) { this.mode = mode; }
    }

    public static class Aws {
        private final DynamoDb dynamodb = new DynamoDb();
        public DynamoDb getDynamodb() { return dynamodb; }
    }

    public static class DynamoDb {
        private boolean captureBefore = true;
        public boolean isCaptureBefore() { return captureBefore; }
        public void setCaptureBefore(boolean captureBefore) { this.captureBefore = captureBefore; }
    }

    public static class Jdbc {
        private JdbcMutationCapture mutationCapture = JdbcMutationCapture.OFF;
        public JdbcMutationCapture getMutationCapture() { return mutationCapture; }
        public void setMutationCapture(JdbcMutationCapture mutationCapture) { this.mutationCapture = mutationCapture; }
    }

    public static class Station {
        private String endpoint;
        private String token;
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        /** Token compartilhado opcional (Authorization: Bearer) para o ingest do Station. */
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    // ------------------------------------------------------------- accessors flat (compatibilidade)

    public TraceVantaConfig toConfig() {
        return TraceVantaConfig.builder()
                .enabled(enabled)
                .basePath(basePath)
                .port(port)
                .bindAddress(bindAddress)
                .allowNonLoopback(allowNonLoopback)
                .bufferCapacity(buffer.capacity)
                .retentionMaxExecutions(retention.maxExecutions)
                .payloadMaxBytes(payload.maxBytes)
                .redactionMode(redaction.mode)
                .dynamoDbCaptureBefore(aws.dynamodb.captureBefore)
                .jdbcMutationCapture(jdbc.mutationCapture)
                .stationEndpoint(station.endpoint)
                .stationToken(station.token)
                .flushTimeoutMs(flushTimeoutMs)
                .quiescenceMs(quiescenceMs)
                .build();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
    public boolean isAllowNonLoopback() { return allowNonLoopback; }
    public void setAllowNonLoopback(boolean allowNonLoopback) { this.allowNonLoopback = allowNonLoopback; }
    public long getFlushTimeoutMs() { return flushTimeoutMs; }
    public void setFlushTimeoutMs(long flushTimeoutMs) { this.flushTimeoutMs = flushTimeoutMs; }
    public long getQuiescenceMs() { return quiescenceMs; }
    public void setQuiescenceMs(long quiescenceMs) { this.quiescenceMs = quiescenceMs; }
    public boolean isIKnowWhatImDoing() { return iKnowWhatImDoing; }
    public void setIKnowWhatImDoing(boolean iKnowWhatImDoing) { this.iKnowWhatImDoing = iKnowWhatImDoing; }

    public Buffer getBuffer() { return buffer; }
    public Retention getRetention() { return retention; }
    public Payload getPayload() { return payload; }
    public Redaction getRedaction() { return redaction; }
    public Aws getAws() { return aws; }
    public Jdbc getJdbc() { return jdbc; }
    public Station getStation() { return station; }

    public int getBufferCapacity() { return buffer.capacity; }
    public void setBufferCapacity(int v) { buffer.capacity = v; }
    public int getRetentionMaxExecutions() { return retention.maxExecutions; }
    public void setRetentionMaxExecutions(int v) { retention.maxExecutions = v; }
    public int getPayloadMaxBytes() { return payload.maxBytes; }
    public void setPayloadMaxBytes(int v) { payload.maxBytes = v; }
    public RedactionMode getRedactionMode() { return redaction.mode; }
    public void setRedactionMode(RedactionMode v) { redaction.mode = v; }
    public boolean isDynamoDbCaptureBefore() { return aws.dynamodb.captureBefore; }
    public void setDynamoDbCaptureBefore(boolean v) { aws.dynamodb.captureBefore = v; }
    public JdbcMutationCapture getJdbcMutationCapture() { return jdbc.mutationCapture; }
    public void setJdbcMutationCapture(JdbcMutationCapture v) { jdbc.mutationCapture = v; }
    public String getStationEndpoint() { return station.endpoint; }
    public void setStationEndpoint(String v) { station.endpoint = v; }
    public String getStationToken() { return station.token; }
    public void setStationToken(String v) { station.token = v; }
}
