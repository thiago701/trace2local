package tech.neural7.trace2local.mocks.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigException;
import tech.neural7.trace2local.mocks.config.ConfigValue;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.config.Password;
import tech.neural7.trace2local.mocks.json.WireMockFormat;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.plugins.sink.EmbeddedSink;
import tech.neural7.trace2local.mocks.plugins.sink.FileExportSink;
import tech.neural7.trace2local.mocks.plugins.sink.WireMockSink;
import tech.neural7.trace2local.mocks.spi.ApiTarget;
import tech.neural7.trace2local.mocks.spi.DeployRequest;
import tech.neural7.trace2local.mocks.spi.MockPlugin;
import tech.neural7.trace2local.mocks.spi.MockPluginException;
import tech.neural7.trace2local.mocks.spi.ObservedExchange;
import tech.neural7.trace2local.mocks.spi.RequestPredicate;
import tech.neural7.trace2local.mocks.spi.ResponseTransform;
import tech.neural7.trace2local.mocks.spi.SourceContext;
import tech.neural7.trace2local.mocks.spi.StubSink;
import tech.neural7.trace2local.mocks.spi.StubSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Worker do Mock Connect (ADR-016) — o equivalente ao worker do Kafka Connect em
 * modo standalone: guarda os bindings (config plana), compila
 * {@code source → transforms → sink}, publica, reporta status
 * ({@code RUNNING/PAUSED/FAILED} + trace acionável) e persiste o config
 * (sem segredos em texto puro).
 */
public final class MockConnectWorker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MockConnectWorker.class.getName());
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{env:[A-Za-z_][A-Za-z0-9_]*}");

    /** Configuração do worker (variáveis {@code TRACE2LOCAL_MOCKS_*} no Station). */
    public record Settings(String bindAddress, int port, String advertisedUrl, Path dataDir, Path stateFile,
                           boolean allowPublicSinks, int journalCapacity, Path pluginPath,
                           Function<String, String> env) {
        public static Settings loopback(int port) {
            return new Settings("127.0.0.1", port, null, null, null, false, 500, null, System::getenv);
        }

        public Settings withDataDir(Path dir) {
            return new Settings(bindAddress, port, advertisedUrl, dir, stateFile, allowPublicSinks, journalCapacity, pluginPath, env);
        }

        public Settings withStateFile(Path file) {
            return new Settings(bindAddress, port, advertisedUrl, dataDir, file, allowPublicSinks, journalCapacity, pluginPath, env);
        }

        public Settings withEnv(Function<String, String> e) {
            return new Settings(bindAddress, port, advertisedUrl, dataDir, stateFile, allowPublicSinks, journalCapacity, pluginPath, e);
        }

        public Settings withAdvertisedUrl(String url) {
            return new Settings(bindAddress, port, url, dataDir, stateFile, allowPublicSinks, journalCapacity, pluginPath, env);
        }
    }

    public enum State { RUNNING, PAUSED, FAILED }

    /** Status de um binding ({@code GET /api/mocks/bindings/{nome}/status}). */
    public record BindingStatus(String name, String state, String api, String target, String source, String sink,
                                String endpoint, int stubs, List<String> transforms, List<String> warnings,
                                String trace, Instant updatedAt, long hits, boolean routing, boolean dynamic) {}

    /** Config (segredos mascarados) + status. */
    public record BindingInfo(String name, Map<String, String> config, BindingStatus status) {}

    /** Rota publicada: chamadas a {@code target} vão para {@code endpoint} (roteamento do cliente). */
    public record MockRoute(String binding, String api, String target, String endpoint) {}

    private static final class Slot {
        Map<String, String> raw;
        BindingConfig cfg;
        State state = State.FAILED;
        CompiledBinding compiled;
        StubSink sink;
        MockConfig sinkConfig;
        StubSink.Deployment deployment;
        List<Stub> published = List.of();
        String trace;
        Instant updatedAt = Instant.now();
        boolean paused;
    }

    private final Settings settings;
    private final PluginRegistry registry;
    private final EmbeddedMockServer server;
    private final MockJournal journal;
    private final Supplier<List<ObservedExchange>> observed;
    private final Map<String, Slot> slots = new LinkedHashMap<>();

    public MockConnectWorker(Settings settings, Supplier<List<ObservedExchange>> observed) {
        this.settings = settings;
        this.observed = observed == null ? List::of : observed;
        this.journal = new MockJournal(settings.journalCapacity());
        this.server = new EmbeddedMockServer(settings.bindAddress(), settings.port(), settings.advertisedUrl(), journal);
        // destinos embutidos primeiro (precisam do servidor/diretório) — nomes reservados
        this.registry = PluginRegistry.load(settings.pluginPath(), List.<MockPlugin>of(
                new EmbeddedSink(server), new WireMockSink(settings.allowPublicSinks()), new FileExportSink(this::resolve)));
    }

    public void start() {
        server.start();
        loadState();
    }

    public int port() {
        return server.port();
    }

    public PluginRegistry registry() {
        return registry;
    }

    public MockJournal journal() {
        return journal;
    }

    // ------------------------------------------------------------------ ciclo de vida

    /** Cria; nome existente = {@link IllegalStateException} (409). Config inválido = {@link ConfigException} (400). */
    public synchronized BindingInfo create(String name, Map<String, String> config) {
        if (slots.containsKey(name)) {
            throw new IllegalStateException("binding '" + name + "' já existe — use PUT .../config para alterar");
        }
        return put(name, config);
    }

    /** Cria ou substitui o config e reinicia (como {@code PUT /connectors/{nome}/config}). */
    public synchronized BindingInfo put(String name, Map<String, String> config) {
        BindingConfig cfg = BindingConfig.parse(name, config, registry, settings.env());
        Slot old = slots.get(name);
        if (old != null) {
            stop(name, old);
        }
        Slot slot = new Slot();
        slot.raw = Map.copyOf(config);
        slot.cfg = cfg;
        slot.paused = old != null && old.paused;
        slots.put(name, slot);
        if (!slot.paused) {
            deploy(name, slot);
        } else {
            slot.state = State.PAUSED;
        }
        saveState();
        return info(name, slot);
    }

    public synchronized boolean delete(String name) {
        Slot slot = slots.remove(name);
        if (slot == null) {
            return false;
        }
        stop(name, slot);
        journal.clear(name);
        saveState();
        return true;
    }

    public synchronized Optional<BindingStatus> pause(String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            return Optional.empty();
        }
        stop(name, slot);
        slot.paused = true;
        slot.state = State.PAUSED;
        slot.updatedAt = Instant.now();
        saveState();
        return Optional.of(status(name, slot));
    }

    public synchronized Optional<BindingStatus> resume(String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            return Optional.empty();
        }
        slot.paused = false;
        deploy(name, slot);
        saveState();
        return Optional.of(status(name, slot));
    }

    /** Recompila e republica (relê o contrato/tráfego, zera contadores de chamada). */
    public synchronized Optional<BindingStatus> restart(String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            return Optional.empty();
        }
        stop(name, slot);
        if (!slot.paused) {
            deploy(name, slot);
        }
        return Optional.of(status(name, slot));
    }

    /** Config bruto (com segredos) — uso interno do conselheiro; nunca exposto pela API REST. */
    public synchronized Optional<Map<String, String>> rawConfig(String name) {
        Slot slot = slots.get(name);
        return slot == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(slot.raw));
    }

    public synchronized Optional<BindingInfo> get(String name) {
        Slot slot = slots.get(name);
        return slot == null ? Optional.empty() : Optional.of(info(name, slot));
    }

    public synchronized List<BindingStatus> list() {
        List<BindingStatus> out = new ArrayList<>();
        slots.forEach((n, s) -> out.add(status(n, s)));
        return out;
    }

    /** Stubs efetivamente publicados (com variações assadas, se o destino for estático). */
    public synchronized Optional<List<Stub>> stubs(String name) {
        Slot slot = slots.get(name);
        return slot == null ? Optional.empty() : Optional.of(slot.published);
    }

    /** Exporta os stubs publicados como mappings do WireMock (download). */
    public synchronized Optional<JsonNode> export(String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            return Optional.empty();
        }
        ObjectNode doc = JsonSupport.MAPPER.createObjectNode();
        ArrayNode mappings = doc.putArray("mappings");
        // exporta COM as variações aplicadas (o que o serviço vê); transformação dinâmica
        // (template, n-ésima chamada) não tem forma estática: exporta a base e avisa
        List<Stub> stubs = slot.published;
        if (slot.compiled != null) {
            try {
                stubs = slot.compiled.bake();
            } catch (MockPluginException e) {
                stubs = slot.compiled.stubs();
                doc.put("warning", "variações dinâmicas não exportadas: " + e.getMessage());
            }
        }
        stubs.forEach(s -> mappings.add(WireMockFormat.write(s, name, 0)));
        return Optional.of(doc);
    }

    public synchronized List<MockRoute> routes() {
        List<MockRoute> out = new ArrayList<>();
        slots.forEach((n, s) -> {
            if (s.state == State.RUNNING && s.cfg.routing() && s.deployment != null
                    && s.deployment.endpoint() != null && s.deployment.endpoint().startsWith("http")) {
                out.add(new MockRoute(n, s.cfg.apiName(), s.cfg.target(), s.deployment.endpoint()));
            }
        });
        return out;
    }

    /** Há binding ativo para este host? (o conselheiro não sugere o que já está plugado) */
    public synchronized Optional<BindingStatus> activeFor(String host, int port) {
        for (Map.Entry<String, Slot> e : slots.entrySet()) {
            Slot s = e.getValue();
            if (new ApiTarget(s.cfg.apiName(), s.cfg.target()).matches(host, port)) {
                return Optional.of(status(e.getKey(), s));
            }
        }
        return Optional.empty();
    }

    public BindingConfig.Report validate(String name, Map<String, String> config) {
        return BindingConfig.validate(name, config, registry, settings.env());
    }

    /** Valida o config de UM plugin ({@code PUT /api/mocks/plugins/{nome}/config/validate}). */
    public Optional<List<ConfigValue>> validatePlugin(String plugin, Map<String, String> config) {
        return registry.any(plugin).map(p -> p.config().validate(config));
    }

    /** Definição de config de um plugin (formulário na UI). */
    public Optional<ConfigDef> pluginConfig(String plugin) {
        return registry.any(plugin).map(MockPlugin::config);
    }

    // ------------------------------------------------------------------ internos

    private void deploy(String name, Slot slot) {
        slot.updatedAt = Instant.now();
        slot.trace = null;
        BindingConfig cfg = slot.cfg;
        ApiTarget target = new ApiTarget(cfg.apiName(), cfg.target());
        try {
            StubSource source = registry.source(cfg.source()).orElseThrow();
            List<Stub> stubs = source.load(source.config().parse(cfg.sourceProps()), context(target));
            List<CompiledBinding.Step> chain = new ArrayList<>();
            for (BindingConfig.TransformRef t : cfg.transforms()) {
                ResponseTransform plugin = registry.transform(t.type()).orElseThrow();
                RequestPredicate.Condition condition = null;
                RequestPredicate.StaticForm form = null;
                if (t.predicate() != null) {
                    BindingConfig.PredicateRef pr = cfg.predicates().get(t.predicate());
                    RequestPredicate rp = registry.predicate(pr.type()).orElseThrow();
                    MockConfig pc = rp.config().parse(pr.props());
                    condition = rp.configure(pc);
                    form = rp.staticForm(pc);
                }
                chain.add(new CompiledBinding.Step(t.alias(), t.type(), plugin.configure(plugin.config().parse(t.props())),
                        plugin.isStatic(), t.predicate(), condition, form, t.negate()));
            }
            List<String> notes = new ArrayList<>();
            stubs.forEach(s -> s.notes().forEach(n -> notes.add(s.id() + ": " + n)));
            CompiledBinding compiled = new CompiledBinding(name, target, cfg.scheme(), cfg.unmatched(), stubs, chain, notes);
            StubSink sink = registry.sink(cfg.sink()).orElseThrow();
            MockConfig sinkConfig = sink.config().parse(cfg.sinkProps());
            List<Stub> toPublish = sink.dynamic() ? compiled.stubs() : compiled.bake();
            slot.deployment = sink.deploy(new DeployRequest(name, target, toPublish, compiled), sinkConfig);
            slot.compiled = compiled;
            slot.sink = sink;
            slot.sinkConfig = sinkConfig;
            slot.published = List.copyOf(toPublish);
            slot.state = State.RUNNING;
        } catch (MockPluginException | ConfigException e) {
            fail(name, slot, e.getMessage());
        } catch (RuntimeException e) {
            fail(name, slot, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void fail(String name, Slot slot, String message) {
        slot.state = State.FAILED;
        slot.trace = message;
        slot.deployment = null;
        slot.published = List.of();
        LOG.warning("Mock Connect: binding '" + name + "' FAILED — " + message);
    }

    private void stop(String name, Slot slot) {
        if (slot.sink != null && slot.sinkConfig != null) {
            try {
                slot.sink.undeploy(name, slot.sinkConfig);
            } catch (RuntimeException e) {
                LOG.fine("Mock Connect: undeploy de '" + name + "' falhou: " + e.getMessage());
            }
        }
        slot.deployment = null;
        slot.published = List.of();
    }

    private SourceContext context(ApiTarget target) {
        return new SourceContext() {
            @Override
            public ApiTarget target() {
                return target;
            }

            @Override
            public List<ObservedExchange> observed() {
                return observed.get().stream().filter(x -> target.matches(x.host(), x.port())).toList();
            }

            @Override
            public Optional<Path> resolve(String relative) {
                return MockConnectWorker.this.resolve(relative);
            }
        };
    }

    /** Caminho DENTRO do diretório de dados (sem absoluto, sem {@code ..}, sem link para fora). */
    Optional<Path> resolve(String relative) {
        Path root = settings.dataDir();
        if (root == null || relative == null || relative.isBlank()) {
            return Optional.empty();
        }
        try {
            Path rel = Path.of(relative);
            if (rel.isAbsolute()) {
                return Optional.empty();
            }
            Path base = root.toAbsolutePath().normalize();
            Path p = base.resolve(rel).normalize();
            if (!p.startsWith(base)) {
                return Optional.empty();
            }
            if (Files.exists(p) && !p.toRealPath().startsWith(base.toRealPath())) {
                return Optional.empty();
            }
            return Optional.of(p);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private BindingStatus status(String name, Slot s) {
        List<String> warnings = new ArrayList<>();
        if (s.compiled != null) {
            warnings.addAll(s.compiled.notes());
        }
        if (s.deployment != null && s.deployment.warnings() != null) {
            warnings.addAll(s.deployment.warnings());
        }
        if (!plaintextSecrets(s.raw).isEmpty()) {
            warnings.add("segredo em texto puro em " + plaintextSecrets(s.raw) + " — não é persistido; prefira ${env:NOME}");
        }
        return new BindingStatus(name, s.state.name(), s.cfg.apiName(), s.cfg.target(), s.cfg.source(), s.cfg.sink(),
                s.deployment == null ? null : s.deployment.endpoint(), s.published.size(),
                s.cfg.transforms().stream().map(t -> t.alias() + " (" + t.type()
                        + (t.predicate() == null ? "" : " se " + (t.negate() ? "não " : "") + t.predicate()) + ")").toList(),
                warnings, s.trace, s.updatedAt, journal.count(name), s.cfg.routing(),
                s.sink != null && s.sink.dynamic());
    }

    private BindingInfo info(String name, Slot s) {
        Map<String, String> masked = new LinkedHashMap<>(s.raw);
        for (String key : plaintextSecrets(s.raw)) {
            masked.put(key, Password.HIDDEN);
        }
        return new BindingInfo(name, masked, status(name, s));
    }

    /** Chaves PASSWORD com valor literal (não {@code ${env:…}}). */
    private List<String> plaintextSecrets(Map<String, String> raw) {
        List<String> out = new ArrayList<>();
        BindingConfig.Report report = BindingConfig.validate("x", raw, registry, k -> "");
        for (BindingConfig.Entry e : report.configs()) {
            String key = e.definition().name();
            String v = raw.get(key);
            if ("PASSWORD".equals(e.definition().type()) && v != null && !v.isBlank() && !ENV_REF.matcher(v).matches()) {
                out.add(key);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ estado

    private void saveState() {
        Path file = settings.stateFile();
        if (file == null) {
            return;
        }
        ObjectNode doc = JsonSupport.MAPPER.createObjectNode().put("version", 1);
        ArrayNode list = doc.putArray("bindings");
        slots.forEach((n, s) -> {
            ObjectNode b = list.addObject().put("name", n).put("paused", s.paused);
            ObjectNode c = b.putObject("config");
            List<String> secrets = plaintextSecrets(s.raw);
            s.raw.forEach((k, v) -> {
                if (!secrets.contains(k)) {
                    c.put(k, v);
                }
            });
        });
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JsonSupport.writePretty(doc));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warning("Mock Connect: não foi possível salvar o estado em " + file + ": " + e.getMessage());
        }
    }

    private synchronized void loadState() {
        Path file = settings.stateFile();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode doc = JsonSupport.MAPPER.readTree(Files.readString(file));
            for (JsonNode b : doc.path("bindings")) {
                Map<String, String> config = new LinkedHashMap<>();
                b.path("config").fields().forEachRemaining(e -> config.put(e.getKey(), e.getValue().asText()));
                loadOne(b.path("name").asText(), config, b.path("paused").asBoolean(false));
            }
        } catch (IOException | RuntimeException e) {
            LOG.warning("Mock Connect: estado ilegível em " + file + " (ignorado): " + e.getMessage());
        }
    }

    /**
     * Carrega bindings declarativos (arquivo de {@code TRACE2LOCAL_MOCKS_CONFIG}, como os
     * {@code .properties} do Connect standalone): {@code [{"name": "...", "config": {...}}]}.
     * Binding inválido entra como FAILED com o motivo — o Station sobe mesmo assim.
     */
    public synchronized List<String> loadDeclarative(Path file) {
        List<String> problems = new ArrayList<>();
        try {
            JsonNode doc = JsonSupport.MAPPER.readTree(Files.readString(file));
            JsonNode list = doc.has("bindings") ? doc.get("bindings") : doc;
            for (JsonNode b : list) {
                Map<String, String> config = new LinkedHashMap<>();
                b.path("config").fields().forEachRemaining(e -> config.put(e.getKey(),
                        e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
                String name = b.path("name").asText();
                if (!slots.containsKey(name)) {
                    String problem = loadOne(name, config, b.path("paused").asBoolean(false));
                    if (problem != null) {
                        problems.add(name + ": " + problem);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            problems.add("arquivo ilegível: " + e.getMessage());
        }
        return problems;
    }

    private String loadOne(String name, Map<String, String> config, boolean paused) {
        try {
            BindingConfig cfg = BindingConfig.parse(name, config, registry, settings.env());
            Slot slot = new Slot();
            slot.raw = Map.copyOf(config);
            slot.cfg = cfg;
            slot.paused = paused;
            slots.put(name, slot);
            if (paused) {
                slot.state = State.PAUSED;
            } else {
                deploy(name, slot);
            }
            saveState();
            return slot.state == State.FAILED ? slot.trace : null;
        } catch (ConfigException e) {
            LOG.warning("Mock Connect: binding '" + name + "' com config inválido ignorado: " + e.getMessage());
            return e.getMessage();
        }
    }

    @Override
    public synchronized void close() {
        slots.forEach((n, s) -> {
            if (s.sink != null && s.sink.undeployOnShutdown()) {
                // destinos externos (WireMock) voltam ao estado do time ao desligar o Station
                stop(n, s);
            }
        });
        server.close();
    }
}
