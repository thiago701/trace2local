package tech.neural7.trace2local.station;

import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.mocks.advisor.ContractCatalog;
import tech.neural7.trace2local.mocks.advisor.MockAdvisor;
import tech.neural7.trace2local.mocks.observe.ExchangeExtractor;
import tech.neural7.trace2local.mocks.rest.MockConnectRestHandler;
import tech.neural7.trace2local.mocks.rest.MockRoutesIngestHandler;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.server.Trace2LocalHttpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Liga o Mock Connect (ADR-016) ao Station a partir do ambiente:
 *
 * <table>
 *   <caption>Variáveis</caption>
 *   <tr><td>{@code TRACE2LOCAL_MOCKS}</td><td>{@code on} (padrão) | {@code off}</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MOCKS_PORT}</td><td>porta do servidor de mocks (padrão 9877)</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MOCKS_ADVERTISED_URL}</td><td>URL que os serviços usam para alcançar os mocks
 *       (ex.: {@code http://trace2local-station:9877} no compose) — como o {@code advertised.listeners} do Kafka</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MOCKS_DIR}</td><td>contratos, mappings e exportações (fontes/destinos só leem/escrevem aqui)</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MOCKS_CONFIG}</td><td>bindings declarativos carregados na subida (JSON)</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MOCKS_STATE_FILE}</td><td>estado persistido (padrão: {@code <DIR>/.trace2local-mocks.json})</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MOCKS_PLUGIN_PATH}</td><td>JARs de plugins de terceiros</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MOCKS_ALLOW_PUBLIC_SINKS}</td><td>libera destino WireMock em host público (padrão false)</td></tr>
 * </table>
 */
final class StationMocks {

    private static final Logger LOG = Logger.getLogger(StationMocks.class.getName());

    private StationMocks() {}

    /** Liga o worker e registra as rotas; {@code null} se desligado. */
    static MockConnectWorker attach(Trace2LocalConfig cfg, Trace2LocalPipeline pipeline, Trace2LocalHttpServer.Builder server) {
        if ("off".equalsIgnoreCase(env("TRACE2LOCAL_MOCKS", "on"))) {
            return null;
        }
        String bind = env("TRACE2LOCAL_MOCKS_BIND", cfg.bindAddress());
        if (!cfg.allowNonLoopback() && !Trace2LocalHttpServer.isLoopback(bind)) {
            LOG.severe("Mock Connect: bind " + bind + " fora do loopback sem TRACE2LOCAL_ALLOW_NON_LOOPBACK=true — mocks desligados");
            return null;
        }
        Path dir = path(env("TRACE2LOCAL_MOCKS_DIR", null));
        Path state = path(env("TRACE2LOCAL_MOCKS_STATE_FILE", dir == null ? null : dir.resolve(".trace2local-mocks.json").toString()));
        MockConnectWorker.Settings settings = new MockConnectWorker.Settings(bind,
                intEnv("TRACE2LOCAL_MOCKS_PORT", 9877), env("TRACE2LOCAL_MOCKS_ADVERTISED_URL", null), dir, state,
                Boolean.parseBoolean(env("TRACE2LOCAL_MOCKS_ALLOW_PUBLIC_SINKS", "false")), 1000,
                path(env("TRACE2LOCAL_MOCKS_PLUGIN_PATH", null)), System::getenv);
        MockConnectWorker worker = new MockConnectWorker(settings, () -> ExchangeExtractor.fromStore(pipeline.store(), 200));
        worker.start();
        String declarative = env("TRACE2LOCAL_MOCKS_CONFIG", null);
        if (declarative != null) {
            Path file = Path.of(declarative);
            if (Files.isRegularFile(file)) {
                worker.loadDeclarative(file).forEach(p -> LOG.warning("Mock Connect: " + p));
            } else {
                LOG.warning("Mock Connect: TRACE2LOCAL_MOCKS_CONFIG não encontrado: " + declarative);
            }
        }
        worker.registry().warnings().forEach(w -> LOG.warning("Mock Connect: " + w));
        ContractCatalog catalog = new ContractCatalog(dir);
        MockAdvisor advisor = new MockAdvisor(() -> recent(pipeline, 150), worker, catalog);
        server.apiRoute("mocks", new MockConnectRestHandler(worker, advisor, catalog));
        server.extraRoute("/t2lingest/v1/mock-routes", new MockRoutesIngestHandler(worker));
        LOG.info(() -> "Mock Connect em http://" + bind + ":" + worker.port() + " — " + worker.registry().plugins().size()
                + " plugins, " + worker.list().size() + " binding(s)" + (dir == null ? " (sem TRACE2LOCAL_MOCKS_DIR: "
                + "fontes openapi/arquivo e exportação desligadas)" : ", dados em " + dir));
        return worker;
    }

    private static List<Execution> recent(Trace2LocalPipeline pipeline, int limit) {
        List<Execution> out = new ArrayList<>();
        pipeline.store().recent(limit).forEach(s -> pipeline.store().get(s.executionId()).ifPresent(out::add));
        return out;
    }

    private static Path path(String v) {
        return v == null || v.isBlank() ? null : Path.of(v).toAbsolutePath().normalize();
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v.trim() : fallback;
    }

    private static int intEnv(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            LOG.warning(key + " inválido — usando " + fallback);
            return fallback;
        }
    }
}
