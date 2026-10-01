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
        // aba API: catálogo e disparo a partir do contrato OpenAPI do serviço (opcional)
        OpenApiEndpoints api = OpenApiEndpoints.fromEnv();
        Trace2LocalHttpServer.Builder builder = Trace2LocalHttpServer.builder(cfg, pipeline)
                .endpoints(() -> api == null ? java.util.List.of() : api.endpoints());
        if (api != null) {
            builder.launcher(api);
        }
        builder
                .extraRoute("/v1/traces", new OtlpTraceReceiver(pipeline, cfg))
                .extraRoute("/t2lingest/v1/mutations", new MutationIngestReceiver(pipeline))
                .extraRoute("/t2lingest/v1/logs", new LogIngestReceiver(pipeline));
        // Mock Connect (ADR-016): plugins de mock de API + conselheiro — ligado por padrão, loopback
        tech.neural7.trace2local.mocks.runtime.MockConnectWorker mocks = StationMocks.attach(cfg, pipeline, builder);
        Trace2LocalMeta base = api == null ? Trace2LocalMeta.station() : Trace2LocalMeta.station().withCapability("execute");
        Trace2LocalMeta meta = mocks == null ? base : base.withCapability("mocks");
        Trace2LocalHttpServer server = builder.meta(() -> meta).build();
        server.start();
        // linha do tempo baseada no CloudWatch (LocalStack) — opcional (ADR-012)
        CloudWatchLogsTail cloudWatch = CloudWatchLogsTail.fromEnv(pipeline.logs());
        LOG.info(() -> "Trace2Local Station em http://" + cfg.bindAddress() + ":" + server.port()
                + cfg.basePath() + " — OTLP em /v1/traces, mutações em /t2lingest/v1/mutations, logs em /t2lingest/v1/logs");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (cloudWatch != null) {
                cloudWatch.close();
            }
            if (mocks != null) {
                mocks.close();
            }
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
                .infraScanDirs(env("TRACE2LOCAL_INFRA_SCAN_DIRS", ""))
                .quiescenceMs(intEnv("TRACE2LOCAL_QUIESCENCE_MS", 5_000))
                .lateContinuationMs(intEnv("TRACE2LOCAL_LATE_CONTINUATION_MS", 600_000))
                .build();
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : defaultValue;
    }

    private static int intEnv(String key, int defaultValue) {
        String v = System.getenv(key);
        try {
            return v != null && !v.isBlank() ? Integer.parseInt(v.trim()) : defaultValue;
        } catch (NumberFormatException e) {
            LOG.warning(key + " inválido (" + v + ") — usando " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean boolEnv(String key, boolean defaultValue) {
        String v = System.getenv(key);
        return v != null ? Boolean.parseBoolean(v) : defaultValue;
    }
}
