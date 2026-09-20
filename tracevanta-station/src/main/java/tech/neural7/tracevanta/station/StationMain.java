package tech.neural7.tracevanta.station;

import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;
import tech.neural7.tracevanta.server.TraceVantaHttpServer;
import tech.neural7.tracevanta.server.TraceVantaMeta;

import java.util.logging.Logger;

/**
 * Station — processo de longa duração do modo Companion (ADR-002 / SPEC §4.2):
 * recebe OTLP/HTTP e o canal de mutação, monta a árvore MULTI-SERVIÇO (vários
 * serviços apontando para o mesmo Station produzem UMA árvore) e serve a UI em
 * {@code :9876}. Uso: {@code java -jar tracevanta-station.jar} ou container
 * {@code docker-compose} ao lado do LocalStack.
 */
public final class StationMain {

    private static final Logger LOG = Logger.getLogger(StationMain.class.getName());

    private StationMain() {}

    public static void main(String[] args) throws Exception {
        TraceVantaConfig cfg = configFromEnv();
        if (!cfg.allowNonLoopback() && !tech.neural7.tracevanta.server.TraceVantaHttpServer.isLoopback(cfg.bindAddress())) {
            LOG.severe("bind fora do loopback (" + cfg.bindAddress() + ") sem TRACEVANTA_ALLOW_NON_LOOPBACK=true — recusando subir (ADR-007/§8.1)");
            System.exit(2);
        }
        if (cfg.allowNonLoopback()) {
            LOG.warning("TRACEVANTA_ALLOW_NON_LOOPBACK=true: a UI está exposta além do loopback, SEM autenticação — consequência declarada no README (ADR-007)");
        }
        TraceVantaPipeline pipeline = TraceVantaPipeline.start(cfg);
        if (cfg.allowNonLoopback() && (cfg.stationToken() == null || cfg.stationToken().isBlank())) {
            LOG.warning("Station exposto além do loopback SEM TRACEVANTA_STATION_TOKEN: "
                    + "o ingest OTLP/mutações está aberto a qualquer um que alcance a porta "
                    + "(ADR-007/§8.1) — defina o token para proteger o ingest.");
        }
        TraceVantaHttpServer server = TraceVantaHttpServer.builder(cfg, pipeline)
                .meta(() -> TraceVantaMeta.station())
                .endpoints(() -> java.util.List.of()) // modo Companion: catálogo vazio, observação pura
                .extraRoute("/v1/traces", new OtlpTraceReceiver(pipeline, cfg))
                .extraRoute("/tvingest/v1/mutations", new MutationIngestReceiver(pipeline))
                .build();
        server.start();
        LOG.info(() -> "TraceVanta Station em http://" + cfg.bindAddress() + ":" + server.port()
                + cfg.basePath() + " — OTLP em /v1/traces, mutações em /tvingest/v1/mutations");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            pipeline.close();
        }));
    }

    static TraceVantaConfig configFromEnv() {
        return TraceVantaConfig.builder()
                .port(intEnv("TRACEVANTA_PORT", 9876))
                .bindAddress(env("TRACEVANTA_BIND_ADDRESS", "127.0.0.1"))
                .allowNonLoopback(boolEnv("TRACEVANTA_ALLOW_NON_LOOPBACK", false))
                .bufferCapacity(intEnv("TRACEVANTA_BUFFER_CAPACITY", 4096))
                .retentionMaxExecutions(intEnv("TRACEVANTA_RETENTION_MAX_EXECUTIONS", 100))
                .stationToken(env("TRACEVANTA_STATION_TOKEN", null))
                .build();
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : defaultValue;
    }

    private static int intEnv(String key, int defaultValue) {
        String v = System.getenv(key);
        return v != null ? Integer.parseInt(v) : defaultValue;
    }

    private static boolean boolEnv(String key, boolean defaultValue) {
        String v = System.getenv(key);
        return v != null ? Boolean.parseBoolean(v) : defaultValue;
    }
}
