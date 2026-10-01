package tech.neural7.trace2local.mocks.runtime;

import tech.neural7.trace2local.mocks.spi.MockPlugin;
import tech.neural7.trace2local.mocks.spi.PluginType;
import tech.neural7.trace2local.mocks.spi.RequestPredicate;
import tech.neural7.trace2local.mocks.spi.ResponseTransform;
import tech.neural7.trace2local.mocks.spi.StubSink;
import tech.neural7.trace2local.mocks.spi.StubSource;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Descoberta de plugins por {@link ServiceLoader}: classpath do Station + um
 * classloader ISOLADO por JAR em {@code TRACE2LOCAL_MOCKS_PLUGIN_PATH} (como o
 * {@code plugin.path} do Kafka Connect — dependências de um plugin não vazam para outro).
 * Conflito de nome: o primeiro registrado vence (embutidos primeiro) e o conflito é reportado.
 */
public final class PluginRegistry {

    private static final Logger LOG = Logger.getLogger(PluginRegistry.class.getName());

    /** Metadados exibidos em {@code GET /api/mocks/plugins}. */
    public record PluginInfo(String name, String type, String version, String description, String className,
                             String origin, boolean dynamicOnly) {}

    private final Map<String, MockPlugin> byKey = new LinkedHashMap<>();
    private final Map<String, String> origins = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private PluginRegistry() {}

    /** Embutidos + JARs do plugin path (pode ser {@code null}). */
    public static PluginRegistry load(Path pluginPath) {
        return load(pluginPath, List.of());
    }

    /**
     * @param builtins plugins com dependência do worker (servidor embutido, diretório de dados) —
     *                 registrados PRIMEIRO: seus nomes são reservados
     */
    public static PluginRegistry load(Path pluginPath, List<MockPlugin> builtins) {
        PluginRegistry r = new PluginRegistry();
        builtins.forEach(b -> r.register(b, "embutido"));
        r.loadFrom(PluginRegistry.class.getClassLoader(), "embutido");
        if (pluginPath != null && Files.isDirectory(pluginPath)) {
            try (Stream<Path> jars = Files.list(pluginPath)) {
                for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                    try {
                        URLClassLoader cl = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                                PluginRegistry.class.getClassLoader());
                        r.loadFrom(cl, jar.getFileName().toString());
                    } catch (IOException e) {
                        r.warnings.add("plugin " + jar.getFileName() + " ignorado: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                r.warnings.add("TRACE2LOCAL_MOCKS_PLUGIN_PATH ilegível: " + e.getMessage());
            }
        }
        return r;
    }

    /** Registro explícito (testes, embutir em outra app). */
    public PluginRegistry register(MockPlugin plugin, String origin) {
        String key = key(plugin.type(), plugin.name());
        if (byKey.containsKey(key)) {
            warnings.add("plugin " + plugin.type() + " '" + plugin.name() + "' de " + origin
                    + " ignorado: nome já registrado por " + origins.get(key));
            return this;
        }
        byKey.put(key, plugin);
        origins.put(key, origin);
        return this;
    }

    private void loadFrom(ClassLoader cl, String origin) {
        try {
            for (MockPlugin p : ServiceLoader.load(MockPlugin.class, cl)) {
                if (origin.equals("embutido") || p.getClass().getClassLoader() == cl) {
                    register(p, origin);
                }
            }
        } catch (ServiceConfigurationError e) {
            warnings.add("falha ao carregar plugins de " + origin + ": " + e.getMessage());
            LOG.warning("Mock Connect: " + e.getMessage());
        }
    }

    public Optional<StubSource> source(String name) {
        return Optional.ofNullable((StubSource) byKey.get(key(PluginType.SOURCE, name)));
    }

    public Optional<StubSink> sink(String name) {
        return Optional.ofNullable((StubSink) byKey.get(key(PluginType.SINK, name)));
    }

    public Optional<ResponseTransform> transform(String name) {
        return Optional.ofNullable((ResponseTransform) byKey.get(key(PluginType.TRANSFORM, name)));
    }

    public Optional<RequestPredicate> predicate(String name) {
        return Optional.ofNullable((RequestPredicate) byKey.get(key(PluginType.PREDICATE, name)));
    }

    /** Busca por nome em qualquer tipo (validação de plugin isolado). */
    public Optional<MockPlugin> any(String name) {
        for (PluginType t : PluginType.values()) {
            MockPlugin p = byKey.get(key(t, name));
            if (p != null) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public List<String> names(PluginType type) {
        List<String> out = new ArrayList<>();
        byKey.values().stream().filter(p -> p.type() == type).forEach(p -> out.add(p.name()));
        return out;
    }

    public List<PluginInfo> plugins() {
        List<PluginInfo> out = new ArrayList<>();
        byKey.forEach((k, p) -> out.add(new PluginInfo(p.name(), p.type().name(), p.version(), p.description(),
                p.getClass().getName(), origins.get(k),
                p instanceof ResponseTransform t && !t.isStatic()
                        || p instanceof RequestPredicate rp && isDynamicOnly(rp))));
        return out;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    private static boolean isDynamicOnly(RequestPredicate p) {
        try {
            return p.staticForm(tech.neural7.trace2local.mocks.config.MockConfig.empty())
                    instanceof RequestPredicate.StaticForm.Unsupported;
        } catch (RuntimeException e) {
            return false; // precisa de config para decidir: estático
        }
    }

    private static String key(PluginType type, String name) {
        return type.name() + ":" + name;
    }
}
