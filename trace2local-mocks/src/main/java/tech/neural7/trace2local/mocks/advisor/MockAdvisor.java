package tech.neural7.trace2local.mocks.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.advisor.MockSuggestion.Evidence;
import tech.neural7.trace2local.mocks.advisor.MockSuggestion.Step;
import tech.neural7.trace2local.mocks.advisor.MockSuggestion.Variation;
import tech.neural7.trace2local.mocks.json.JsonPointers;
import tech.neural7.trace2local.mocks.observe.ExchangeExtractor;
import tech.neural7.trace2local.mocks.openapi.OpenApiDocument;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;
import tech.neural7.trace2local.mocks.spi.ObservedExchange;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.otel.OtelAttributeNames;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Conselheiro de mocks: lê as execuções e diz ao dev QUANDO vale plugar um mock
 * no lugar de uma API e QUAIS variações de resposta validar no microsserviço alvo.
 * Determinístico, local (nada sai da máquina) e com evidência navegável.
 *
 * <table>
 *   <caption>Regras</caption>
 *   <tr><th>kind</th><th>sinal</th></tr>
 *   <tr><td>UNAVAILABLE_DEPENDENCY</td><td>chamada sem resposta (conexão recusada, DNS, timeout) ou 502/503/504 —
 *       a API não existe/não é acessível no ambiente local</td></tr>
 *   <tr><td>RESPONSE_DRIVES_FLOW</td><td>o valor de um campo da resposta separa execuções com caminhos
 *       diferentes (ex.: decision=APPROVED liquida, REVIEW manda para revisão)</td></tr>
 *   <tr><td>HAPPY_PATH_ONLY</td><td>só respostas de sucesso foram exercitadas para a API</td></tr>
 *   <tr><td>SLOW_DEPENDENCY</td><td>a API domina o tempo da execução</td></tr>
 *   <tr><td>CONTRACT_DRIFT</td><td>resposta real fora do contrato (campo obrigatório ausente, enum desconhecido)</td></tr>
 * </table>
 */
public final class MockAdvisor {

    /** Cabeçalho W3C {@code baggage}: a variação escolhida por requisição atravessa os serviços instrumentados. */
    public static final String BAGGAGE_KEY = "t2l.mock";

    /** Identificadores e marcas de tempo (camelCase/snake_case) — variam por execução, não decidem fluxo. */
    private static final Pattern VOLATILE_KEY = Pattern.compile(
            "(id|ID|.*Id|.*ID|.*_id|uuid|.*Uuid|.*_uuid|timestamp|.*Timestamp|time|.*Time|.*_time|date|.*Date|.*_date"
            + "|.*At|.*_at|created.*|updated.*|token|.*Token|.*_token|nonce|e2e.*|endToEnd.*|txid|txId|correlation.*"
            + "|request.*|trace.*|etag)");
    private static final Pattern VOLATILE_VALUE = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\\d{4}-\\d{2}-\\d{2}T.*|E\\d{8,}.*");
    private static final Set<String> NETWORK_ERRORS = Set.of("connectexception", "unknownhostexception",
            "unresolvedaddressexception", "httpconnecttimeoutexception", "httptimeoutexception", "sockettimeoutexception",
            "noroutetohostexception", "closedchannelexception", "connectionclosed", "eofexception");

    private final Supplier<List<Execution>> executions;
    private final MockConnectWorker worker;
    private final ContractCatalog contracts;

    public MockAdvisor(Supplier<List<Execution>> executions, MockConnectWorker worker, ContractCatalog contracts) {
        this.executions = executions;
        this.worker = worker;
        this.contracts = contracts == null ? new ContractCatalog(null) : contracts;
    }

    /** Todas as sugestões, mais graves primeiro. */
    public List<MockSuggestion> suggest() {
        List<Execution> execs = executions.get();
        Map<String, Execution> byId = new LinkedHashMap<>();
        execs.forEach(e -> byId.put(e.executionId(), e));
        List<ObservedExchange> all = new ArrayList<>();
        execs.forEach(e -> all.addAll(ExchangeExtractor.fromExecution(e)));
        Map<String, List<ObservedExchange>> byHost = all.stream()
                .collect(Collectors.groupingBy(ObservedExchange::hostPort, LinkedHashMap::new, Collectors.toList()));
        List<MockSuggestion> out = new ArrayList<>();
        byHost.forEach((host, xs) -> {
            unavailable(host, xs, byId).ifPresent(out::add);
            out.addAll(drivesFlow(host, xs, byId));
            happyPathOnly(host, xs).ifPresent(out::add);
            slow(host, xs, byId).ifPresent(out::add);
            drift(host, xs).ifPresent(out::add);
        });
        // mesma API vista em hosts diferentes (ex.: container e localhost): o título diz qual
        Map<String, Long> sameApi = out.stream().collect(Collectors.groupingBy(s -> s.kind() + "|" + s.api(), Collectors.counting()));
        List<MockSuggestion> titled = out.stream().map(s -> sameApi.get(s.kind() + "|" + s.api()) > 1
                ? new MockSuggestion(s.id(), s.kind(), s.severity(), s.title() + " (" + s.target() + ")", s.why(), s.api(),
                s.target(), s.operation(), s.evidence(), s.variations(), s.bindingName(), s.bindingConfig(), s.howTo(),
                s.activeBinding()) : s).collect(Collectors.toCollection(ArrayList::new));
        titled.sort(Comparator.comparingInt((MockSuggestion s) -> rank(s.severity())).thenComparing(MockSuggestion::title));
        return titled;
    }

    /** Sugestões com evidência na execução indicada. */
    public List<MockSuggestion> forExecution(String executionId) {
        return suggest().stream()
                .filter(s -> s.evidence().stream().anyMatch(e -> executionId.equals(e.executionId())))
                .toList();
    }

    public Optional<MockSuggestion> byId(String id) {
        return suggest().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /**
     * Aplica: cria/atualiza o binding da sugestão com as variações escolhidas.
     *
     * @param mode {@code exclusive} (variação sempre ativa) ou {@code on-demand}
     *             (só quando a requisição traz {@code baggage: t2l.mock=<id>})
     */
    public MockConnectWorker.BindingInfo apply(MockSuggestion s, List<String> variationIds, String mode,
                                               Map<String, String> overrides) {
        if (worker == null) {
            throw new IllegalStateException("Mock Connect desligado neste modo");
        }
        Map<String, String> config = new LinkedHashMap<>(worker.rawConfig(s.bindingName()).orElse(s.bindingConfig()));
        if (overrides != null) {
            config.putAll(overrides);
        }
        // remove as variações aplicadas antes pelo conselheiro (aliases v-*/p-*/on-*)
        List<String> transforms = new ArrayList<>(list(config.get("transforms")));
        List<String> predicates = new ArrayList<>(list(config.get("predicates")));
        transforms.removeIf(MockAdvisor::managed);
        predicates.removeIf(MockAdvisor::managed);
        config.keySet().removeIf(k -> (k.startsWith("transforms.") || k.startsWith("predicates.")) && managed(k.split("\\.")[1]));
        boolean onDemand = "on-demand".equals(mode);
        // nenhuma variação pedida = só pluga o mock (resposta base); variações são sempre explícitas
        List<String> wanted = variationIds == null ? List.of() : variationIds;
        List<Variation> chosen = s.variations().stream().filter(v -> wanted.contains(v.id())).toList();
        if (chosen.size() != wanted.size()) {
            throw new IllegalArgumentException("variação desconhecida; disponíveis: "
                    + s.variations().stream().map(Variation::id).toList());
        }
        for (Variation v : chosen) {
            String predicateAlias = null;
            if (onDemand) {
                if (v.predicate() != null) {
                    continue; // já tem predicado próprio (ex.: n-ésima chamada): não é selecionável por baggage
                }
                predicateAlias = "on-" + v.id();
                predicates.add(predicateAlias);
                config.put("predicates." + predicateAlias + ".type", "header-matches");
                config.put("predicates." + predicateAlias + ".name", "baggage");
                config.put("predicates." + predicateAlias + ".regex",
                        "(?:^|.*[,\\s])" + Pattern.quote(BAGGAGE_KEY + "=" + v.id()) + "(?:[,;\\s].*)?");
            } else if (v.predicate() != null) {
                predicateAlias = "p-" + v.id();
                predicates.add(predicateAlias);
                String a = predicateAlias;
                v.predicate().forEach((k, val) -> config.put("predicates." + a + "." + k, val));
            } else if (v.operation() != null) {
                predicateAlias = "p-" + v.id();
                predicates.add(predicateAlias);
                config.put("predicates." + predicateAlias + ".type", "path-matches");
                config.put("predicates." + predicateAlias + ".pattern", v.operation().substring(v.operation().indexOf(' ') + 1));
            }
            for (int i = 0; i < v.steps().size(); i++) {
                Step step = v.steps().get(i);
                String alias = "v-" + v.id() + (v.steps().size() > 1 ? "-" + (i + 1) : "");
                transforms.add(alias);
                config.put("transforms." + alias + ".type", step.type());
                step.props().forEach((k, val) -> config.put("transforms." + alias + "." + k, val));
                if (predicateAlias != null) {
                    config.put("transforms." + alias + ".predicate", predicateAlias);
                }
            }
        }
        putList(config, "transforms", transforms);
        putList(config, "predicates", predicates);
        return worker.put(s.bindingName(), config);
    }

    // ------------------------------------------------------------------ regras

    private Optional<MockSuggestion> unavailable(String host, List<ObservedExchange> xs, Map<String, Execution> byId) {
        List<ObservedExchange> real = xs.stream().filter(x -> !x.mocked()).toList();
        if (real.isEmpty()) {
            return Optional.empty();
        }
        ObservedExchange latest = real.get(0);
        if (!isUnavailable(latest)) {
            return Optional.empty();
        }
        List<ObservedExchange> failures = real.stream().filter(MockAdvisor::isUnavailable).toList();
        List<ObservedExchange> successes = real.stream().filter(ObservedExchange::successful).toList();
        ObservedExchange sample = failures.get(0);
        String api = apiName(sample);
        Optional<ContractCatalog.Contract> contract = contracts.forHost(sample.host(), sample.port());
        Map<String, String> cfg = baseConfig(sample, api);
        String sourceText;
        if (!successes.isEmpty()) {
            cfg.put("source", "observed");
            sourceText = "replay das " + successes.size() + " resposta(s) reais já observadas";
        } else if (contract.isPresent()) {
            cfg.put("source", "openapi");
            cfg.put("source.spec", contract.get().path());
            sourceText = "stubs gerados do contrato " + contract.get().path();
        } else {
            cfg.put("source", "inline");
            cfg.put("source.stubs", skeleton(failures));
            sourceText = "esqueleto com as operações chamadas (complete os corpos — sem contrato nem resposta real observada)";
        }
        cfg.put("unmatched", "not-found");
        boolean brokeExecution = failures.stream().map(x -> byId.get(x.executionId()))
                .anyMatch(e -> e != null && e.status() == ExecutionStatus.FAILED);
        List<Evidence> evidence = failures.stream().limit(5).map(x -> new Evidence(x.executionId(), x.nodeId(),
                x.operation() + " → " + (x.status() > 0 ? "HTTP " + x.status() : simple(x.errorType())))).toList();
        String why = api + " não respondeu em " + failures.size() + " de " + real.size() + " chamada(s) ("
                + simple(sample.errorType() != null ? sample.errorType() : "HTTP " + sample.status())
                + "): a API não está disponível neste ambiente. Plugue um mock (" + sourceText
                + ") para seguir desenvolvendo e testando o fluxo sem depender dela.";
        List<Variation> variations = new ArrayList<>(resilience(null));
        return Optional.of(new MockSuggestion(id("UNAVAILABLE", host, ""), "UNAVAILABLE_DEPENDENCY",
                brokeExecution ? "HIGH" : "MEDIUM", api + " indisponível no ambiente local — plugue um mock", why,
                api, host, sample.operation(), evidence, variations, bindingName(api), cfg, howTo(api), active(sample)));
    }

    private List<MockSuggestion> drivesFlow(String host, List<ObservedExchange> xs, Map<String, Execution> byId) {
        List<MockSuggestion> out = new ArrayList<>();
        Map<String, List<ObservedExchange>> byOp = xs.stream().filter(ObservedExchange::successful)
                .filter(x -> x.responseBody() != null)
                .collect(Collectors.groupingBy(ObservedExchange::operation, LinkedHashMap::new, Collectors.toList()));
        byOp.forEach((op, list) -> {
            // uma troca por execução (a primeira), execuções conhecidas
            Map<String, ObservedExchange> perExec = new LinkedHashMap<>();
            list.forEach(x -> perExec.putIfAbsent(x.executionId(), x));
            if (perExec.size() < 2) {
                return;
            }
            Map<String, String> signature = new LinkedHashMap<>();
            Map<String, Map<String, JsonNode>> leaves = new LinkedHashMap<>();
            perExec.forEach((execId, x) -> {
                Execution e = byId.get(execId);
                JsonNode body = JsonSupport.parse(x.responseBody());
                if (e != null && body != null) {
                    signature.put(execId, signature(e, x.host()));
                    Map<String, JsonNode> l = new LinkedHashMap<>();
                    JsonPointers.leaves(body, "", 3, l);
                    leaves.put(execId, l);
                }
            });
            if (new LinkedHashSet<>(signature.values()).size() < 2) {
                return; // todas as execuções seguiram o mesmo caminho
            }
            Set<String> pointers = new TreeSet<>();
            leaves.values().forEach(l -> pointers.addAll(l.keySet()));
            List<Driver> drivers = new ArrayList<>();
            for (String p : pointers) {
                Map<String, Set<String>> valueToSig = new LinkedHashMap<>();
                Map<String, List<String>> valueToExecs = new LinkedHashMap<>();
                boolean volatileValue = false;
                for (Map.Entry<String, Map<String, JsonNode>> e : leaves.entrySet()) {
                    JsonNode v = e.getValue().get(p);
                    String key = v == null ? "∅" : v.isNull() ? "null" : v.asText();
                    volatileValue |= VOLATILE_VALUE.matcher(key).matches();
                    valueToSig.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(signature.get(e.getKey()));
                    valueToExecs.computeIfAbsent(key, k -> new ArrayList<>()).add(e.getKey());
                }
                String last = p.substring(p.lastIndexOf('/') + 1);
                if (valueToSig.size() < 2 || volatileValue || VOLATILE_KEY.matcher(last).matches()) {
                    continue;
                }
                boolean enumLike = valueToSig.keySet().stream().allMatch(MockAdvisor::enumLike);
                if (valueToSig.size() == leaves.size() && leaves.size() >= 3 && !enumLike) {
                    continue; // valor único por execução e sem cara de enum: identificador, não decisão
                }
                boolean consistent = valueToSig.values().stream().allMatch(s -> s.size() == 1);
                long distinctSigs = valueToSig.values().stream().flatMap(Set::stream).distinct().count();
                if (consistent && distinctSigs >= 2) {
                    boolean numeric = leaves.values().stream().map(l -> l.get(p)).anyMatch(v -> v != null && v.isNumber());
                    drivers.add(new Driver(p, valueToSig, valueToExecs, numeric));
                }
            }
            if (drivers.isEmpty()) {
                return;
            }
            // categórico (texto/booleano, poucos valores) primeiro
            drivers.sort(Comparator.comparing(Driver::numeric).thenComparingInt(d -> d.valueToSig().size()));
            Driver d = drivers.get(0);
            ObservedExchange sample = list.get(0);
            String api = apiName(sample);
            List<Evidence> evidence = new ArrayList<>();
            StringBuilder why = new StringBuilder("O microsserviço segue caminhos diferentes conforme ")
                    .append(d.pointer()).append(" na resposta de ").append(api).append(' ').append(op).append(": ");
            List<String> parts = new ArrayList<>();
            List<String> values = new ArrayList<>(d.valueToSig().keySet());
            String firstSig = d.valueToSig().get(values.get(0)).iterator().next();
            for (String value : values) {
                String sig = d.valueToSig().get(value).iterator().next();
                String execId = d.valueToExecs().get(value).get(0);
                evidence.add(new Evidence(execId, perExec.get(execId).nodeId(), d.pointer() + " = " + value));
                parts.add(value + " → " + describe(sig, value.equals(values.get(0)) ? null : firstSig));
            }
            why.append(String.join("; ", parts)).append('.');
            if (drivers.size() > 1) {
                why.append(" Também variam junto: ").append(drivers.stream().skip(1).limit(3).map(Driver::pointer)
                        .collect(Collectors.joining(", "))).append('.');
            }
            why.append(" Valide os outros valores possíveis sem depender de a API real produzi-los.");
            List<Variation> variations = new ArrayList<>();
            String field = d.pointer();
            for (String value : values) {
                if (!value.equals("∅")) {
                    variations.add(setField(op, field, value, "BRANCH", field + " = " + value,
                            "valor observado (" + d.valueToExecs().get(value).size() + " execução(ões))"));
                }
            }
            contracts.forHost(sample.host(), sample.port()).ifPresent(c -> {
                OpenApiDocument doc = c.document();
                operationOf(doc, op).ifPresent(o -> {
                    String status = doc.pickStatus(o, String.valueOf(sample.status()));
                    List<String> enums = doc.enums(o, status).get(field);
                    if (enums != null) {
                        enums.stream().filter(v -> !values.contains(v)).forEach(v -> variations.add(setField(op, field, v,
                                "CONTRACT", field + " = " + v, "previsto no contrato " + c.path() + " e nunca observado")));
                    }
                });
            });
            variations.add(new Variation(slug(field) + "-null", field + " = null", "EDGE",
                    "parceiro devolve o campo nulo", List.of(new Step("set-field", Map.of("pointer", field, "value", "null"))),
                    null, op));
            variations.add(new Variation(slug(field) + "-ausente", field + " ausente", "EDGE",
                    "parceiro omite o campo (versão nova do contrato, bug do parceiro)",
                    List.of(new Step("remove-field", Map.of("pointer", field))), null, op));
            Map<String, String> cfg = baseConfig(sample, api);
            cfg.put("source", "proxy"); // API real continua respondendo; variações agem sobre a resposta real
            out.add(new MockSuggestion(id("DRIVES", host, op + field), "RESPONSE_DRIVES_FLOW", "MEDIUM",
                    "A resposta de " + api + " decide o fluxo: " + field, why.toString(), api, host, op, evidence,
                    variations, bindingName(api), cfg, howTo(api), active(sample)));
        });
        return out;
    }

    private record Driver(String pointer, Map<String, Set<String>> valueToSig, Map<String, List<String>> valueToExecs,
                          boolean numeric) {}

    private Optional<MockSuggestion> happyPathOnly(String host, List<ObservedExchange> xs) {
        if (xs.isEmpty() || xs.stream().anyMatch(x -> !x.successful())) {
            return Optional.empty();
        }
        List<ObservedExchange> real = xs.stream().filter(x -> !x.mocked()).toList();
        if (real.isEmpty()) {
            return Optional.empty();
        }
        ObservedExchange sample = real.get(0);
        String api = apiName(sample);
        Map<String, Long> ops = real.stream().collect(Collectors.groupingBy(ObservedExchange::operation,
                LinkedHashMap::new, Collectors.counting()));
        String mainOp = ops.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(sample.operation());
        List<Variation> variations = new ArrayList<>(resilience(null));
        variations.addAll(edges(sample, mainOp));
        Map<String, String> cfg = baseConfig(sample, api);
        cfg.put("source", "proxy"); // API real continua respondendo; variações agem sobre a resposta real
        List<Evidence> evidence = real.stream().limit(5)
                .map(x -> new Evidence(x.executionId(), x.nodeId(), x.operation() + " → HTTP " + x.status())).toList();
        String why = "As " + real.size() + " chamada(s) observadas a " + api + " (" + String.join(", ", ops.keySet())
                + ") só exercitaram sucesso. O tratamento de erro, timeout, retry e campos ausentes do microsserviço"
                + " nunca rodou — plugue o mock com variações para cobrir esses caminhos antes da homologação.";
        return Optional.of(new MockSuggestion(id("HAPPY", host, ""), "HAPPY_PATH_ONLY", "LOW",
                "Só o caminho feliz de " + api + " foi exercitado", why, api, host, mainOp, evidence, variations,
                bindingName(api), cfg, howTo(api), active(sample)));
    }

    private Optional<MockSuggestion> slow(String host, List<ObservedExchange> xs, Map<String, Execution> byId) {
        List<ObservedExchange> real = xs.stream().filter(x -> !x.mocked() && x.successful()).toList();
        if (real.size() < 2) {
            return Optional.empty();
        }
        double avg = real.stream().mapToLong(x -> x.duration().toMillis()).average().orElse(0);
        double share = real.stream().mapToDouble(x -> {
            Execution e = byId.get(x.executionId());
            long total = e == null || e.duration() == null ? 0 : e.duration().toMillis();
            return total <= 0 ? 0 : (double) x.duration().toMillis() / total;
        }).average().orElse(0);
        if (avg < 1000 && !(share >= 0.4 && avg >= 300)) {
            return Optional.empty();
        }
        ObservedExchange sample = real.get(0);
        String api = apiName(sample);
        Map<String, String> cfg = baseConfig(sample, api);
        cfg.put("source", "proxy"); // API real continua respondendo; variações agem sobre a resposta real
        List<Evidence> evidence = real.stream().sorted(Comparator.comparing(ObservedExchange::duration).reversed()).limit(3)
                .map(x -> new Evidence(x.executionId(), x.nodeId(), x.operation() + " levou " + x.duration().toMillis() + " ms"))
                .toList();
        String why = String.format(Locale.ROOT, "%s responde em %.0f ms em média (%.0f%% do tempo das execuções). "
                + "Um mock com replay do que já foi observado deixa o ciclo local rápido; a variação de latência "
                + "reproduz o pior caso sob demanda.", api, avg, share * 100);
        List<Variation> variations = List.of(
                new Variation("latencia-observada", "latência observada (" + Math.round(avg) + " ms)", "RESILIENCE",
                        "reproduz o tempo real do parceiro", List.of(new Step("latency", Map.of("ms", String.valueOf(Math.round(avg))))),
                        null, null),
                new Variation("latencia-3x", "3× a latência observada", "RESILIENCE", "pior caso: o timeout do cliente aguenta?",
                        List.of(new Step("latency", Map.of("ms", String.valueOf(Math.round(avg * 3))))), null, null));
        return Optional.of(new MockSuggestion(id("SLOW", host, ""), "SLOW_DEPENDENCY", share >= 0.6 ? "MEDIUM" : "LOW",
                api + " domina o tempo das execuções", why, api, host, sample.operation(), evidence, variations,
                bindingName(api), cfg, howTo(api), active(sample)));
    }

    private Optional<MockSuggestion> drift(String host, List<ObservedExchange> xs) {
        List<ObservedExchange> real = xs.stream().filter(x -> !x.mocked() && x.successful() && x.responseBody() != null).toList();
        if (real.isEmpty()) {
            return Optional.empty();
        }
        ObservedExchange sample = real.get(0);
        Optional<ContractCatalog.Contract> contract = contracts.forHost(sample.host(), sample.port());
        if (contract.isEmpty()) {
            return Optional.empty();
        }
        OpenApiDocument doc = contract.get().document();
        List<Evidence> evidence = new ArrayList<>();
        Set<String> problems = new LinkedHashSet<>();
        for (ObservedExchange x : real) {
            Optional<OpenApiDocument.Operation> op = operationOf(doc, x.operation());
            JsonNode body = JsonSupport.parse(x.responseBody());
            if (op.isEmpty() || body == null) {
                continue;
            }
            String status = doc.pickStatus(op.get(), String.valueOf(x.status()));
            for (String req : doc.requiredFields(op.get(), status)) {
                if (JsonPointers.get(body, req) == null) {
                    String p = x.operation() + ": campo obrigatório " + req + " ausente";
                    if (problems.add(p)) {
                        evidence.add(new Evidence(x.executionId(), x.nodeId(), p));
                    }
                }
            }
            doc.enums(op.get(), status).forEach((pointer, allowed) -> {
                JsonNode v = JsonPointers.get(body, pointer);
                if (v != null && v.isValueNode() && !v.isNull() && !allowed.contains(v.asText())
                        && !v.asText().contains("TRACE2LOCAL_REDACTED")) {
                    String p = x.operation() + ": " + pointer + " = " + v.asText() + " fora do enum " + allowed;
                    if (problems.add(p)) {
                        evidence.add(new Evidence(x.executionId(), x.nodeId(), p));
                    }
                }
            });
        }
        if (problems.isEmpty()) {
            return Optional.empty();
        }
        String api = apiName(sample);
        Map<String, String> cfg = baseConfig(sample, api);
        cfg.put("source", "openapi");
        cfg.put("source.spec", contract.get().path());
        cfg.put("unmatched", "proxy");
        String why = "A resposta real de " + api + " diverge do contrato " + contract.get().path() + ": "
                + String.join("; ", problems.stream().limit(4).toList())
                + ". Ou o contrato está desatualizado, ou o parceiro mudou — confirme antes de homologar."
                + " Um mock pelo contrato isola o seu serviço enquanto isso se resolve.";
        return Optional.of(new MockSuggestion(id("DRIFT", host, String.join("|", problems)), "CONTRACT_DRIFT", "MEDIUM",
                api + " responde fora do contrato", why, api, host, sample.operation(), evidence, List.of(),
                bindingName(api), cfg, howTo(api), active(sample)));
    }

    // ------------------------------------------------------------------ variações

    static List<Variation> resilience(String operation) {
        return List.of(
                new Variation("http-503", "Indisponível (503)", "RESILIENCE", "parceiro fora do ar: o serviço degrada com elegância?",
                        List.of(new Step("set-status", Map.of("code", "503", "body", "{\"error\":\"service unavailable\"}"))), null, operation),
                new Variation("http-500", "Erro interno (500)", "RESILIENCE", "erro inesperado do parceiro",
                        List.of(new Step("set-status", Map.of("code", "500", "body", "{\"error\":\"internal error\"}"))), null, operation),
                new Variation("http-429", "Limite de taxa (429 + Retry-After)", "RESILIENCE", "throttling: respeita Retry-After?",
                        List.of(new Step("set-status", Map.of("code", "429", "body", "{\"error\":\"too many requests\"}")),
                                new Step("set-header", Map.of("name", "Retry-After", "value", "2"))), null, operation),
                new Variation("falha-transitoria", "503 só na 1ª chamada", "RESILIENCE", "falha transitória: o retry recupera?",
                        List.of(new Step("set-status", Map.of("code", "503"))), Map.of("type", "call-count", "calls", "1"), operation),
                new Variation("timeout", "Timeout (15 s sem resposta)", "RESILIENCE", "o timeout do cliente está configurado?",
                        List.of(new Step("fault", Map.of("kind", "timeout", "timeout.ms", "15000"))), null, operation),
                new Variation("lento", "Lentidão (3 s)", "RESILIENCE", "latência alta: SLA e timeouts encadeados",
                        List.of(new Step("latency", Map.of("ms", "3000"))), null, operation),
                new Variation("conexao-resetada", "Conexão derrubada", "RESILIENCE", "falha de rede no meio da chamada",
                        List.of(new Step("fault", Map.of("kind", "connection-reset"))), null, operation),
                new Variation("resposta-vazia", "Resposta vazia/corrompida", "RESILIENCE", "o parser do cliente quebra com elegância?",
                        List.of(new Step("fault", Map.of("kind", "empty-response"))), null, operation));
    }

    private List<Variation> edges(ObservedExchange sample, String op) {
        List<Variation> out = new ArrayList<>();
        JsonNode body = JsonSupport.parse(sample.responseBody());
        List<String> fields = new ArrayList<>();
        contracts.forHost(sample.host(), sample.port()).flatMap(c -> operationOf(c.document(), op).map(o ->
                c.document().requiredFields(o, c.document().pickStatus(o, String.valueOf(sample.status())))))
                .ifPresent(fields::addAll);
        if (fields.isEmpty() && body != null && body.isObject()) {
            body.fieldNames().forEachRemaining(f -> {
                if (!VOLATILE_KEY.matcher(f).matches()) {
                    fields.add("/" + JsonPointers.escape(f));
                }
            });
        }
        fields.stream().limit(3).forEach(f -> out.add(new Variation(slug(f) + "-ausente", f + " ausente", "EDGE",
                "campo que o serviço lê pode faltar", List.of(new Step("remove-field", Map.of("pointer", f))), null, op)));
        if (body != null && body.isObject()) {
            out.add(new Variation("campo-extra", "Campo extra desconhecido", "EDGE",
                    "tolerant reader: campo novo do parceiro não pode quebrar a desserialização",
                    List.of(new Step("set-field", Map.of("pointer", "/campoNovoDoParceiro", "value", "\"x\""))), null, op));
        }
        return out;
    }

    private static Variation setField(String op, String field, String value, String category, String title, String rationale) {
        String json = value.equals("null") ? "null" : JsonSupport.write(JsonSupport.MAPPER.getNodeFactory().textNode(value));
        if (value.matches("-?\\d+(\\.\\d+)?") || value.equals("true") || value.equals("false")) {
            json = value; // número/booleano mantém o tipo
        }
        return new Variation(slug(field + "-" + value), title, category, rationale,
                List.of(new Step("set-field", Map.of("pointer", field, "value", json))), null, op);
    }

    // ------------------------------------------------------------------ apoio

    /** APPROVED, IN_REVIEW, true, PIX… — vocabulário de decisão, não identificador. */
    static boolean enumLike(String v) {
        return v.matches("[A-Z][A-Z0-9_]{1,30}") || v.equals("true") || v.equals("false") || v.equals("null") || v.equals("∅")
                || (v.length() <= 12 && v.matches("[A-Za-z_-]+"));
    }

    private static boolean isUnavailable(ObservedExchange x) {
        if (x.failedWithoutResponse()) {
            String t = simple(x.errorType()).toLowerCase(Locale.ROOT);
            return NETWORK_ERRORS.contains(t) || t.contains("connect") || t.contains("timeout")
                    || t.contains("unknownhost") || t.contains("unresolved") || (x.errorMessage() != null
                    && x.errorMessage().toLowerCase(Locale.ROOT).matches(".*(refused|unreachable|resolve|timed out|reset).*"));
        }
        return x.status() == 502 || x.status() == 503 || x.status() == 504;
    }

    /** Assinatura do caminho da execução (status + passos), sem as chamadas ao próprio parceiro. */
    static String signature(Execution e, String partnerHost) {
        Set<String> labels = new TreeSet<>();
        String rootStatus = "";
        for (Node r : e.roots()) {
            if (r.attributes() != null && r.attributes().get(OtelAttributeNames.HTTP_STATUS) != null && rootStatus.isEmpty()) {
                rootStatus = r.attributes().get(OtelAttributeNames.HTTP_STATUS);
            }
            collect(r, partnerHost, labels);
        }
        return e.status() + "|" + rootStatus + "|" + String.join("¦", labels);
    }

    private static void collect(Node n, String partnerHost, Set<String> labels) {
        boolean partner = n.kind() == NodeKind.HTTP_CLIENT && n.attributes() != null
                && partnerHost.equalsIgnoreCase(n.attributes().getOrDefault(OtelAttributeNames.SERVER_ADDRESS, ""));
        if (!partner && n.kind() != NodeKind.UNKNOWN) {
            labels.add(n.kind() + ":" + n.label() + (n.status() != null && n.status().name().equals("ERROR") ? "!" : ""));
        }
        if (n.children() != null) {
            n.children().forEach(c -> collect(c, partnerHost, labels));
        }
    }

    /** "+SQS: liquidação, −SNS: revisão, status 202" — diferença legível entre dois caminhos. */
    static String describe(String sig, String reference) {
        String[] p = sig.split("\\|", 3);
        String status = p[0] + (p[1].isEmpty() ? "" : " (HTTP " + p[1] + ")");
        if (reference == null) {
            return "execução " + status.toLowerCase(Locale.ROOT);
        }
        String[] r = reference.split("\\|", 3);
        Set<String> a = new TreeSet<>(List.of(p[2].split("¦")));
        Set<String> b = new TreeSet<>(List.of(r[2].split("¦")));
        List<String> diff = new ArrayList<>();
        a.stream().filter(x -> !b.contains(x) && !x.isBlank()).limit(3).forEach(x -> diff.add("+" + pretty(x)));
        b.stream().filter(x -> !a.contains(x) && !x.isBlank()).limit(3).forEach(x -> diff.add("−" + pretty(x)));
        if (!p[0].equals(r[0]) || !p[1].equals(r[1])) {
            diff.add(0, "execução " + status.toLowerCase(Locale.ROOT));
        }
        return diff.isEmpty() ? "mesmo caminho" : String.join(", ", diff);
    }

    private static String pretty(String label) {
        int colon = label.indexOf(':');
        return colon > 0 ? label.substring(colon + 1) : label;
    }

    private static Optional<OpenApiDocument.Operation> operationOf(OpenApiDocument doc, String op) {
        String base = doc.basePath();
        for (OpenApiDocument.Operation o : doc.operations()) {
            String method = o.method();
            String pattern = (base + o.path()).replaceAll("\\{[^}]+}", "{id}");
            String observed = op.substring(op.indexOf(' ') + 1);
            if (op.startsWith(method + " ") && (pattern.equals(observed)
                    || tech.neural7.trace2local.mocks.model.PathTemplate.of(base + o.path()).matches(observed.replace("{id}", "x")))) {
                return Optional.of(o);
            }
        }
        return Optional.empty();
    }

    private Map<String, String> baseConfig(ObservedExchange x, String api) {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("target", x.hostPort());
        cfg.put("api.name", api);
        cfg.put("sink", "embedded");
        return cfg;
    }

    private static String skeleton(List<ObservedExchange> failures) {
        var arr = JsonSupport.MAPPER.createArrayNode();
        failures.stream().map(ObservedExchange::operation).distinct().forEach(op -> {
            var stub = arr.addObject();
            stub.put("method", op.substring(0, op.indexOf(' ')));
            stub.put("path", op.substring(op.indexOf(' ') + 1));
            stub.put("status", op.startsWith("POST") ? 201 : 200);
            stub.putObject("body");
        });
        return JsonSupport.write(arr);
    }

    private List<String> howTo(String api) {
        List<String> out = new ArrayList<>();
        out.add("Roteamento automático: clientes instrumentados com Trace2LocalHttp e TRACE2LOCAL_MOCKS_ROUTING=on "
                + "passam a chamar o mock sem mudar URL — o nó na árvore fica marcado como SIMULADO.");
        out.add("Ou aponte a URL base de " + api + " no seu serviço para o endpoint do binding (status do mock).");
        out.add("Variação por requisição (modo sob demanda): envie o cabeçalho W3C 'baggage: " + BAGGAGE_KEY
                + "=<id-da-variação>' na chamada ao seu serviço; ele atravessa os serviços instrumentados até o mock.");
        return out;
    }

    private String active(ObservedExchange x) {
        return worker == null ? null : worker.activeFor(x.host(), x.port()).map(MockConnectWorker.BindingStatus::name).orElse(null);
    }

    private static String apiName(ObservedExchange x) {
        return x.peerService() != null && !x.peerService().isBlank() ? x.peerService() : x.host();
    }

    static String bindingName(String api) {
        return "mock-" + slug(api);
    }

    static String slug(String s) {
        String out = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return out.isEmpty() ? "api" : out.length() > 40 ? out.substring(0, 40) : out;
    }

    private static String id(String kind, String host, String extra) {
        return kind.toLowerCase(Locale.ROOT) + "-" + Integer.toHexString((host + "|" + extra).hashCode());
    }

    private static String simple(String type) {
        if (type == null) {
            return "?";
        }
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private static boolean managed(String alias) {
        return alias.startsWith("v-") || alias.startsWith("p-") || alias.startsWith("on-");
    }

    private static List<String> list(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static void putList(Map<String, String> config, String key, List<String> values) {
        if (values.isEmpty()) {
            config.remove(key);
        } else {
            config.put(key, String.join(",", values));
        }
    }

    static Duration ms(long v) {
        return Duration.ofMillis(v);
    }
}
