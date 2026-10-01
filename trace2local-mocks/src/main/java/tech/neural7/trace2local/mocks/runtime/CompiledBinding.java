package tech.neural7.trace2local.mocks.runtime;

import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.model.MockRequest;
import tech.neural7.trace2local.mocks.model.MockResponse;
import tech.neural7.trace2local.mocks.model.RequestMatcher;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.spi.ApiTarget;
import tech.neural7.trace2local.mocks.spi.CallContext;
import tech.neural7.trace2local.mocks.spi.MockPluginException;
import tech.neural7.trace2local.mocks.spi.RequestPredicate;
import tech.neural7.trace2local.mocks.spi.ResponseTransform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Binding pronto para servir: stubs ordenados (prioridade → especificidade → ordem),
 * cadeia de transformações com predicados e contadores por stub. Imutável exceto
 * pelos contadores (reiniciados a cada deploy — {@code call-count} reproduzível).
 */
public final class CompiledBinding {

    /** Um passo da cadeia: transformação configurada + predicado opcional. */
    public record Step(String alias, String plugin, ResponseTransform.Transformation fn, boolean isStatic,
                       String predicateAlias, RequestPredicate.Condition condition,
                       RequestPredicate.StaticForm staticForm, boolean negate) {}

    /** Por que um stub quase casou (resposta 404 do mock — near-miss estilo WireMock). */
    public record NearMiss(String stubId, String operation, List<String> differences, int distance) {}

    /** Resultado de uma requisição. {@code stub == null} = nenhum casou. */
    public record Outcome(Stub stub, MockResponse response, List<String> applied, Map<String, String> pathParams,
                          List<NearMiss> nearMisses) {}

    private final String name;
    private final ApiTarget target;
    private final String scheme;
    private final String unmatched;
    private final List<Stub> stubs;
    private final List<Step> chain;
    private final List<String> notes;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final SplittableRandom random = new SplittableRandom();

    CompiledBinding(String name, ApiTarget target, String scheme, String unmatched, List<Stub> stubs, List<Step> chain,
                    List<String> notes) {
        this.name = name;
        this.target = target;
        this.scheme = scheme;
        this.unmatched = unmatched;
        List<Stub> sorted = new ArrayList<>(stubs);
        List<Stub> original = List.copyOf(stubs);
        sorted.sort(Comparator.comparingInt(Stub::priority)
                .thenComparing(Comparator.comparingInt((Stub s) -> s.request().specificity()).reversed())
                .thenComparingInt(original::indexOf));
        this.stubs = List.copyOf(sorted);
        this.chain = List.copyOf(chain);
        this.notes = List.copyOf(notes);
    }

    public String name() { return name; }
    public ApiTarget target() { return target; }
    public String scheme() { return scheme; }
    public String unmatched() { return unmatched; }
    public List<Stub> stubs() { return stubs; }
    public List<Step> chain() { return chain; }
    public List<String> notes() { return notes; }

    /** Casa a requisição e aplica a cadeia (destino dinâmico — servidor embutido). */
    public Outcome respond(MockRequest request) {
        return respond(request, null);
    }

    /**
     * Como {@link #respond(MockRequest)}; em stub de repasse ({@link Stub#passthrough()}),
     * {@code upstream} obtém a resposta REAL e a cadeia de transformações age sobre ela.
     */
    public Outcome respond(MockRequest request, java.util.function.Function<MockRequest, MockResponse> upstream) {
        for (Stub stub : stubs) {
            if (stub.request().matches(request)) {
                long n = counters.computeIfAbsent(stub.id(), k -> new AtomicLong()).incrementAndGet();
                Map<String, String> params = stub.request().template().extract(request.path());
                CallContext ctx;
                synchronized (random) {
                    ctx = new CallContext(name, stub.id(), n, params, random.split());
                }
                MockResponse response = stub.passthrough() && upstream != null ? upstream.apply(request) : stub.response();
                List<String> applied = new ArrayList<>();
                for (Step step : chain) {
                    boolean on = step.condition() == null || step.condition().test(request, ctx);
                    if (step.negate()) {
                        on = !on;
                    }
                    if (on) {
                        try {
                            response = step.fn().apply(request, response);
                            applied.add(step.alias());
                        } catch (RuntimeException e) {
                            throw new MockPluginException("transformação '" + step.alias() + "' (" + step.plugin()
                                    + ") falhou: " + e.getMessage(), e);
                        }
                    }
                }
                return new Outcome(stub, response, applied, params, List.of());
            }
        }
        return new Outcome(null, null, List.of(), Map.of(), nearMisses(request));
    }

    /** Zera os contadores de chamada (reset de cenário entre testes). */
    public void resetCounters() {
        counters.clear();
    }

    public long hits(String stubId) {
        AtomicLong a = counters.get(stubId);
        return a == null ? 0 : a.get();
    }

    private List<NearMiss> nearMisses(MockRequest r) {
        List<NearMiss> out = new ArrayList<>();
        for (Stub s : stubs) {
            List<String> diff = new ArrayList<>();
            int d = 0;
            RequestMatcher m = s.request();
            if (m.method() != null && !m.method().equals(r.method())) {
                diff.add("método " + r.method() + " ≠ " + m.method());
                d += 5;
            }
            if (!m.template().matches(r.path())) {
                diff.add("caminho " + r.path() + " não casa " + m.path());
                d += Math.min(20, ConfigDef.levenshteinDistance(m.template().samplePath(), r.path()));
            }
            m.query().forEach((k, v) -> {
                if (!v.equals(r.queryParam(k))) {
                    diff.add("query " + k + "=" + r.queryParam(k) + " (esperado " + v + ")");
                }
            });
            for (RequestMatcher.Constraint c : m.constraints()) {
                if (!new RequestMatcher(null, "/**", Map.of(), List.of(c)).matches(r)) {
                    diff.add((c.kind() == RequestMatcher.Constraint.Kind.HEADER ? "cabeçalho " : "corpo ") + c.key()
                            + " não casa /" + c.regex() + "/");
                    d += 3;
                }
            }
            d += diff.size();
            out.add(new NearMiss(s.id(), s.operation(), diff, d));
        }
        out.sort(Comparator.comparingInt(NearMiss::distance));
        return out.size() > 3 ? List.copyOf(out.subList(0, 3)) : out;
    }

    /**
     * "Assa" a cadeia nos stubs para destinos ESTÁTICOS (WireMock, exportação):
     * predicado sobre o stub decide se o stub inteiro recebe a transformação; predicado
     * de cabeçalho/corpo gera um stub ADICIONAL mais prioritário com a restrição.
     */
    public List<Stub> bake() {
        if (stubs.stream().anyMatch(Stub::passthrough) && !chain.isEmpty()) {
            throw new MockPluginException("repasse (source=proxy) com transformações só funciona no destino embedded "
                    + "— a resposta real é transformada a cada requisição");
        }
        List<Stub> current = new ArrayList<>(stubs);
        for (Step step : chain) {
            if (!step.isStatic()) {
                throw new MockPluginException("transformação '" + step.alias() + "' não pode ser publicada num destino estático");
            }
            List<Stub> next = new ArrayList<>();
            for (Stub s : current) {
                MockRequest sample = sampleRequest(s);
                if (step.staticForm() == null) {
                    next.add(s.withResponse(step.fn().apply(sample, s.response())));
                } else if (step.staticForm() instanceof RequestPredicate.StaticForm.OnStub on) {
                    boolean apply = on.test().test(s) != step.negate();
                    next.add(apply ? s.withResponse(step.fn().apply(sample, s.response())) : s);
                } else if (step.staticForm() instanceof RequestPredicate.StaticForm.ExtraConstraint extra) {
                    next.add(s);
                    next.add(s.withRequest(s.request().with(extra.constraint()))
                            .withResponse(step.fn().apply(sample, s.response()))
                            .withId(s.id() + "~" + step.alias(), Math.max(1, s.priority() - 1)));
                } else if (step.staticForm() instanceof RequestPredicate.StaticForm.Unsupported u) {
                    throw new MockPluginException(u.reason());
                }
            }
            current = next;
        }
        return current;
    }

    private static MockRequest sampleRequest(Stub s) {
        return MockRequest.of(s.request().method() == null ? "GET" : s.request().method(), s.request().template().samplePath());
    }
}
