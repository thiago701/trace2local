package tech.neural7.trace2local.station;

import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.server.Trace2LocalHttpServer;
import tech.neural7.trace2local.server.Trace2LocalMeta;

import java.util.logging.Logger;

/**
 * Station — processo de longa duração do modo Companion (ADR-002 / SPEC §4.2):
 * recebe OTLP/HTTP e o canal de mutação, monta a árvore MULTI-SERVIÇO (vários
 * serviços apontando para o mesmo Station produzem UMA árvore) e serve a UI em
 * {@code :9876}. Uso: {@code java -jar trace2local-station.jar} ou container
 * {@code docker-compose} ao lado do LocalStack.
 */
public final class StationMain {

    private static final Logger LOG = Logger.getLogger(StationMain.class.getName());

    private StationMain() {}

    public static void main(String[] args) throws Exception {
        Trace2LocalConfig cfg = configFromEnv();
        if (!cfg.allowNonLoopback() && !tech.neural7.trace2local.server.Trace2LocalHttpServer.isLoopback(cfg.bindAddress())) {
            LOG.severe("bind fora do loopback (" + cfg.bindAddress() + ") sem TRACE2LOCAL_ALLOW_NON_LOOPBACK=true — recusando subir (ADR-007/§8.1)");
            System.exit(2);
        }
        if (cfg.allowNonLoopback()) {
            LOG.warning("TRACE2LOCAL_ALLOW_NON_LOOPBACK=true: a UI está exposta além do loopback, SEM autenticação — consequência declarada no README (ADR-007)");
        }
        Trace2LocalPipeline pipeline = Trace2LocalPipeline.start(cfg);
        if (cfg.allowNonLoopback() && (cfg.stationToken() == null || cfg.stationToken().isBlank())) {
            LOG.warning("Station exposto além do loopback SEM TRACE2LOCAL_STATION_TOKEN: "
                    + "o ingest OTLP/mutações está aberto a qualquer um que alcance a porta "
                    + "(ADR-007/§8.1) — defina o token para proteger o ingest.");
        }
        Trace2LocalHttpServer server = Trace2LocalHttpServer.builder(cfg, pipeline)
                .meta(() -> Trace2LocalMeta.station())
                .endpoints(() -> java.util.List.of()) // modo Companion: catálogo vazio, observação pura
                .extraRoute("/v1/traces", new OtlpTraceReceiver(pipeline, cfg))
                .extraRoute("/t2lingest/v1/mutations", new MutationIngestReceiver(pipeline))
                .build();
        server.start();
        LOG.info(() -> "Trace2Local Station em http://" + cfg.bindAddress() + ":" + server.port()
                + cfg.basePath() + " — OTLP em /v1/traces, mutações em /t2lingest/v1/mutations");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            pipeline.close();
        }));
    }

    static Trace2LocalConfig configFromEnv() {
        return Trace2LocalConfig.builder()
                .port(intEnv("TRACE2LOCAL_PORT", 9876))
                .bindAddress(env("TRACE2LOCAL_BIND_ADDRESS", "127.0.0.1"))
                .allowNonLoopback(boolEnv("TRACE2LOCAL_ALLOW_NON_LOOPBACK", false))
                .bufferCapacity(intEnv("TRACE2LOCAL_BUFFER_CAPACITY", 4096))
                .retentionMaxExecutions(intEnv("TRACE2LOCAL_RETENTION_MAX_EXECUTIONS", 100))
                .stationToken(env("TRACE2LOCAL_STATION_TOKEN", null))
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
