package tech.neural7.trace2local.mocks.runtime;

import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.ConfigException;
import tech.neural7.trace2local.mocks.config.ConfigValue;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.config.Validators;
import tech.neural7.trace2local.mocks.spi.MockPlugin;
import tech.neural7.trace2local.mocks.spi.RequestPredicate;
import tech.neural7.trace2local.mocks.spi.ResponseTransform;
import tech.neural7.trace2local.mocks.spi.StubSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Config PLANA de um binding, no formato de um conector do Kafka Connect:
 *
 * <pre>
 * target=antifraude.parceiro:8080
 * source=openapi                      (ou observed · inline · proxy — repasse com variações)
 * source.spec=contracts/antifraude.yaml
 * sink=embedded
 * transforms=negado,lento
 * transforms.negado.type=set-field
 * transforms.negado.pointer=/decision
 * transforms.negado.value=DENIED
 * transforms.negado.predicate=score
 * transforms.lento.type=latency
 * transforms.lento.ms=1500
 * predicates=score
 * predicates.score.type=path-matches
 * predicates.score.pattern=/v1/score
 * </pre>
 *
 * Valores aceitam {@code ${env:NOME}} (config provider): o segredo fica no ambiente,
 * o config persistido guarda só a referência.
 */
public final class BindingConfig {

    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    public static final ConfigDef CORE = new ConfigDef()
            .group("Binding")
            .define("target", Type.STRING, ConfigDef.NO_DEFAULT, Validators.hostAndPort(), Importance.HIGH,
                    "host[:porta] da API real que o mock substitui (ex.: antifraude.parceiro:8080)")
            .define("target.scheme", Type.STRING, "http", Validators.in("http", "https"), Importance.LOW,
                    "esquema da API real (usado por unmatched=proxy)", "http", "https")
            .define("api.name", Type.STRING, null, null, Importance.MEDIUM, "nome lógico na UI (padrão: nome do binding)")
            .define("description", Type.STRING, null, null, Importance.LOW, "para que serve este mock")
            .group("Pipeline")
            .define("source", Type.STRING, ConfigDef.NO_DEFAULT, Validators.nonEmpty(), Importance.HIGH,
                    "plugin de origem dos stubs", "openapi", "observed", "inline", "proxy")
            .define("sink", Type.STRING, "embedded", Validators.nonEmpty(), Importance.HIGH,
                    "plugin de destino", "embedded", "wiremock", "file")
            .define("transforms", Type.LIST, List.of(), null, Importance.MEDIUM,
                    "aliases das transformações, na ordem em que são aplicadas")
            .define("predicates", Type.LIST, List.of(), null, Importance.LOW, "aliases dos predicados usados pelas transformações")
            .define("unmatched", Type.STRING, "not-found", Validators.in("not-found", "proxy", "error"), Importance.MEDIUM,
                    "requisição sem stub: 404 com near-misses, proxy para a API real ou 500", "not-found", "proxy", "error")
            .define("routing", Type.BOOLEAN, true, null, Importance.MEDIUM,
                    "publica a rota para clientes Trace2LocalHttp com TRACE2LOCAL_MOCKS_ROUTING=on");

    public record TransformRef(String alias, String type, String predicate, boolean negate, Map<String, Object> props) {}

    public record PredicateRef(String alias, String type, Map<String, Object> props) {}

    /** Uma linha do relatório de validação (definição + valor), formato {@code ConfigInfos} do Connect. */
    public record Entry(Definition definition, ConfigValue value) {}

    public record Definition(String name, String type, boolean required, Object defaultValue, String importance,
                             String documentation, String group, List<String> recommendedValues) {}

    public record Report(String name, int errorCount, List<String> groups, List<Entry> configs) {
        public Map<String, List<String>> errors() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            configs.forEach(e -> {
                if (!e.value().errors().isEmpty()) {
                    out.put(e.value().name(), e.value().errors());
                }
            });
            return out;
        }
    }

    private final String name;
    private final Map<String, String> raw;
    private final MockConfig core;
    private final Map<String, Object> sourceProps;
    private final Map<String, Object> sinkProps;
    private final List<TransformRef> transforms;
    private final Map<String, PredicateRef> predicates;

    private BindingConfig(String name, Map<String, String> raw, MockConfig core, Map<String, Object> sourceProps,
                          Map<String, Object> sinkProps, List<TransformRef> transforms, Map<String, PredicateRef> predicates) {
        this.name = name;
        this.raw = raw;
        this.core = core;
        this.sourceProps = sourceProps;
        this.sinkProps = sinkProps;
        this.transforms = transforms;
        this.predicates = predicates;
    }

    public String name() { return name; }
    public Map<String, String> raw() { return raw; }
    public MockConfig core() { return core; }
    public String target() { return core.getString("target"); }
    public String apiName() { return core.has("api.name") ? core.getString("api.name") : name; }
    public String source() { return core.getString("source"); }
    public String sink() { return core.getString("sink"); }
    public Map<String, Object> sourceProps() { return sourceProps; }
    public Map<String, Object> sinkProps() { return sinkProps; }
    public List<TransformRef> transforms() { return transforms; }
    public Map<String, PredicateRef> predicates() { return predicates; }
    public String unmatched() { return core.getString("unmatched"); }
    public boolean routing() { return core.getBoolean("routing"); }
    public String scheme() { return core.getString("target.scheme"); }

    /** Interpreta e valida tudo; erro = {@link ConfigException} com TODOS os problemas. */
    public static BindingConfig parse(String name, Map<String, String> raw, PluginRegistry registry,
                                      Function<String, String> env) {
        Report report = validate(name, raw, registry, env);
        if (report.errorCount() > 0) {
            throw new ConfigException(report.errors());
        }
        Map<String, String> resolved = resolveEnv(raw, env, new LinkedHashMap<>());
        Split s = split(resolved);
        MockConfig core = CORE.parse(s.core);
        List<TransformRef> transforms = new ArrayList<>();
        for (String alias : core.getList("transforms")) {
            Map<String, Object> props = new LinkedHashMap<>(s.transforms.getOrDefault(alias, Map.of()));
            String type = String.valueOf(props.remove("type"));
            Object predicate = props.remove("predicate");
            Object negate = props.remove("negate");
            transforms.add(new TransformRef(alias, type, predicate == null ? null : String.valueOf(predicate),
                    negate != null && Boolean.parseBoolean(String.valueOf(negate)), props));
        }
        Map<String, PredicateRef> predicates = new LinkedHashMap<>();
        for (String alias : core.getList("predicates")) {
            Map<String, Object> props = new LinkedHashMap<>(s.predicates.getOrDefault(alias, Map.of()));
            String type = String.valueOf(props.remove("type"));
            predicates.put(alias, new PredicateRef(alias, type, props));
        }
        return new BindingConfig(name, Map.copyOf(raw), core, s.source, s.sink, transforms, predicates);
    }

    /** Relatório completo (o que {@code PUT .../config/validate} devolve). Nunca lança. */
    public static Report validate(String name, Map<String, String> raw, PluginRegistry registry,
                                  Function<String, String> env) {
        List<Entry> entries = new ArrayList<>();
        Set<String> groups = new LinkedHashSet<>();
        if (name == null || !NAME.matcher(name).matches()) {
            entries.add(errorEntry("name", name, "nome inválido: use [a-z0-9][a-z0-9._-]* (vira caminho de URL)"));
        }
        Map<String, String> envErrors = new LinkedHashMap<>();
        Map<String, String> resolved = resolveEnv(raw == null ? Map.of() : raw, env, envErrors);
        envErrors.forEach((k, msg) -> entries.add(errorEntry(k, raw.get(k), msg)));
        Split s = split(resolved);

        addAll(entries, groups, CORE, s.core, "");
        // segue validando os plugins mesmo com erro em chaves do núcleo (ex.: target) —
        // o dev recebe TODOS os problemas de uma vez
        Map<String, Object> coreValues = new LinkedHashMap<>();
        for (ConfigValue v : CORE.validate(s.core)) {
            if (v.errors().isEmpty() && CORE.keys().containsKey(v.name())) {
                coreValues.put(v.name(), v.value());
            }
        }
        LenientCore core = new LenientCore(coreValues);
        if (core.getString("source") != null) {
            Optional<? extends MockPlugin> source = registry.source(core.getString("source"));
            if (source.isEmpty()) {
                entries.add(errorEntry("source", core.getString("source"),
                        "plugin de origem desconhecido; disponíveis: " + registry.names(tech.neural7.trace2local.mocks.spi.PluginType.SOURCE)));
            } else {
                addAll(entries, groups, source.get().config(), s.source, "source.", "Origem: " + source.get().name());
            }
            Optional<StubSink> sink = registry.sink(core.getString("sink"));
            if (sink.isEmpty()) {
                entries.add(errorEntry("sink", core.getString("sink"),
                        "plugin de destino desconhecido; disponíveis: " + registry.names(tech.neural7.trace2local.mocks.spi.PluginType.SINK)));
            } else {
                addAll(entries, groups, sink.get().config(), s.sink, "sink.", "Destino: " + sink.get().name());
            }
            boolean dynamicSink = sink.map(StubSink::dynamic).orElse(true);
            List<String> predicateAliases = core.getList("predicates");
            for (String alias : predicateAliases) {
                Map<String, Object> props = new LinkedHashMap<>(s.predicates.getOrDefault(alias, Map.of()));
                Object type = props.remove("type");
                String prefix = "predicates." + alias + ".";
                if (type == null) {
                    entries.add(errorEntry(prefix + "type", null, "obrigatório: tipo do predicado (ex.: path-matches)"));
                    continue;
                }
                Optional<RequestPredicate> p = registry.predicate(String.valueOf(type));
                if (p.isEmpty()) {
                    entries.add(errorEntry(prefix + "type", type, "predicado desconhecido; disponíveis: "
                            + registry.names(tech.neural7.trace2local.mocks.spi.PluginType.PREDICATE)));
                    continue;
                }
                addAll(entries, groups, p.get().config(), props, prefix, "Predicado: " + alias + " (" + type + ")");
                if (!dynamicSink) {
                    try {
                        MockConfig pc = p.get().config().parse(props);
                        if (p.get().staticForm(pc) instanceof RequestPredicate.StaticForm.Unsupported u) {
                            entries.add(errorEntry(prefix + "type", type, "destino '" + core.getString("sink")
                                    + "' é estático: " + u.reason()));
                        }
                    } catch (ConfigException ignored) {
                        // já reportado
                    }
                }
            }
            for (String alias : s.predicates.keySet()) {
                if (!predicateAliases.contains(alias)) {
                    entries.add(errorEntry("predicates." + alias, null, "alias '" + alias + "' não está na lista 'predicates'"));
                }
            }
            List<String> transformAliases = core.getList("transforms");
            for (String alias : transformAliases) {
                Map<String, Object> props = new LinkedHashMap<>(s.transforms.getOrDefault(alias, Map.of()));
                Object type = props.remove("type");
                Object predicate = props.remove("predicate");
                Object negate = props.remove("negate");
                String prefix = "transforms." + alias + ".";
                if (type == null) {
                    entries.add(errorEntry(prefix + "type", null, "obrigatório: tipo da transformação (ex.: set-field)"));
                    continue;
                }
                Optional<ResponseTransform> t = registry.transform(String.valueOf(type));
                if (t.isEmpty()) {
                    entries.add(errorEntry(prefix + "type", type, "transformação desconhecida; disponíveis: "
                            + registry.names(tech.neural7.trace2local.mocks.spi.PluginType.TRANSFORM)));
                    continue;
                }
                addAll(entries, groups, t.get().config(), props, prefix, "Transformação: " + alias + " (" + type + ")");
                if (!dynamicSink && !t.get().isStatic()) {
                    entries.add(errorEntry(prefix + "type", type, "'" + type + "' depende da requisição; o destino '"
                            + core.getString("sink") + "' é estático — use sink=embedded"));
                }
                if (predicate != null && !predicateAliases.contains(String.valueOf(predicate))) {
                    entries.add(errorEntry(prefix + "predicate", predicate, "predicado '" + predicate
                            + "' não está em 'predicates' (declare predicates=" + predicate + ")"));
                }
                if (negate != null && !"true".equalsIgnoreCase(String.valueOf(negate)) && !"false".equalsIgnoreCase(String.valueOf(negate))) {
                    entries.add(errorEntry(prefix + "negate", negate, "esperado true ou false"));
                }
                if (negate != null && Boolean.parseBoolean(String.valueOf(negate)) && !dynamicSink && predicate != null) {
                    PredicateRef pr = predicateRef(s, String.valueOf(predicate));
                    if (pr != null && registry.predicate(pr.type()).map(rp -> {
                        try {
                            return rp.staticForm(rp.config().parse(pr.props())) instanceof RequestPredicate.StaticForm.ExtraConstraint;
                        } catch (ConfigException e) {
                            return false;
                        }
                    }).orElse(false)) {
                        entries.add(errorEntry(prefix + "negate", negate,
                                "negate com predicado de cabeçalho/corpo não é expressável num destino estático — use sink=embedded"));
                    }
                }
            }
            for (String alias : s.transforms.keySet()) {
                if (!transformAliases.contains(alias)) {
                    entries.add(errorEntry("transforms." + alias, null, "alias '" + alias
                            + "' não está na lista 'transforms' (a transformação seria ignorada)"));
                }
            }
        }
        groups.add("Binding");
        int errors = (int) entries.stream().filter(e -> !e.value().errors().isEmpty()).count();
        List<String> orderedGroups = new ArrayList<>();
        orderedGroups.add("Binding");
        orderedGroups.add("Pipeline");
        groups.stream().filter(g -> !orderedGroups.contains(g)).forEach(orderedGroups::add);
        return new Report(name, errors, orderedGroups, entries);
    }

    /** Valores válidos do núcleo (os inválidos ficam ausentes) — validação tolerante. */
    private record LenientCore(Map<String, Object> values) {
        String getString(String key) {
            Object v = values.get(key);
            return v == null ? null : String.valueOf(v);
        }

        @SuppressWarnings("unchecked")
        List<String> getList(String key) {
            Object v = values.get(key);
            return v instanceof List<?> l ? (List<String>) l : List.of();
        }
    }

    private static PredicateRef predicateRef(Split s, String alias) {
        Map<String, Object> props = s.predicates.get(alias);
        if (props == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(props);
        Object type = copy.remove("type");
        return type == null ? null : new PredicateRef(alias, String.valueOf(type), copy);
    }

    private static void addAll(List<Entry> out, Set<String> groups, ConfigDef def, Map<String, ?> props, String prefix) {
        addAll(out, groups, def, props, prefix, null);
    }

    private static void addAll(List<Entry> out, Set<String> groups, ConfigDef def, Map<String, ?> props, String prefix,
                               String groupOverride) {
        for (ConfigValue v : def.validate(props)) {
            ConfigDef.Key key = def.keys().get(v.name());
            String group = groupOverride != null ? groupOverride : key != null ? key.group() : "Binding";
            groups.add(group);
            Definition d = key == null
                    ? new Definition(prefix + v.name(), "UNKNOWN", false, null, "LOW", "chave desconhecida", group, List.of())
                    : new Definition(prefix + key.name(), key.type().name(), key.required(),
                    key.required() ? null : shownDefault(key), key.importance().name(), key.documentation(), group,
                    key.recommendedValues());
            out.add(new Entry(d, new ConfigValue(prefix + v.name(), v.value(), v.recommendedValues(), v.errors(), v.visible())));
        }
    }

    private static Object shownDefault(ConfigDef.Key key) {
        Object d = key.defaultValue();
        return d instanceof com.fasterxml.jackson.databind.JsonNode n ? n.toString() : d;
    }

    private static Entry errorEntry(String name, Object value, String message) {
        return new Entry(new Definition(name, "STRING", false, null, "HIGH", "", "Binding", List.of()),
                new ConfigValue(name, value, List.of(), List.of(message), true));
    }

    /** Resolve {@code ${env:NOME}}; variável ausente vira erro na chave (nunca string vazia silenciosa). */
    static Map<String, String> resolveEnv(Map<String, String> raw, Function<String, String> env, Map<String, String> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v == null) {
                out.put(k, null);
                return;
            }
            Matcher m = ENV_REF.matcher(v);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String value = env == null ? null : env.apply(m.group(1));
                if (value == null) {
                    errors.put(k, "variável de ambiente " + m.group(1) + " não definida");
                    value = "";
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            out.put(k, sb.toString());
        });
        return out;
    }

    private record Split(Map<String, Object> core, Map<String, Object> source, Map<String, Object> sink,
                         Map<String, Map<String, Object>> transforms, Map<String, Map<String, Object>> predicates) {}

    private static Split split(Map<String, String> flat) {
        Map<String, Object> core = new LinkedHashMap<>();
        Map<String, Object> source = new LinkedHashMap<>();
        Map<String, Object> sink = new LinkedHashMap<>();
        Map<String, Map<String, Object>> transforms = new LinkedHashMap<>();
        Map<String, Map<String, Object>> predicates = new LinkedHashMap<>();
        flat.forEach((k, v) -> {
            if (k.startsWith("source.")) {
                source.put(k.substring(7), v);
            } else if (k.startsWith("sink.")) {
                sink.put(k.substring(5), v);
            } else if (k.startsWith("transforms.") && k.indexOf('.', 11) > 11) {
                int dot = k.indexOf('.', 11);
                transforms.computeIfAbsent(k.substring(11, dot), a -> new LinkedHashMap<>()).put(k.substring(dot + 1), v);
            } else if (k.startsWith("predicates.") && k.indexOf('.', 11) > 11) {
                int dot = k.indexOf('.', 11);
                predicates.computeIfAbsent(k.substring(11, dot), a -> new LinkedHashMap<>()).put(k.substring(dot + 1), v);
            } else if (!k.equals("name")) {
                core.put(k, v);
            }
        });
        return new Split(core, source, sink, transforms, predicates);
    }
}
