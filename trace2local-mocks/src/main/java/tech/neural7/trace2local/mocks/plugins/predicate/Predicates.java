package tech.neural7.trace2local.mocks.plugins.predicate;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.ConfigException;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.config.Validators;
import tech.neural7.trace2local.mocks.model.PathTemplate;
import tech.neural7.trace2local.mocks.model.RequestMatcher;
import tech.neural7.trace2local.mocks.spi.RequestPredicate;

import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

/** Predicados embutidos — decidem QUANDO uma transformação vale. */
public final class Predicates {

    private Predicates() {}

    /** {@code path-matches}: caminho por template ({@code /v1/score}, {@code /entries/{key}}, glob ou {@code re:}). */
    public static final class PathMatches implements RequestPredicate {
        @Override public String name() { return "path-matches"; }
        @Override public String description() {
            return "Aplica a transformação só para um caminho (template /entries/{key}, glob /v1/* ou re:<regex>).";
        }
        @Override public ConfigDef config() {
            return new ConfigDef().define("pattern", Type.STRING, ConfigDef.NO_DEFAULT, Validators.nonEmpty(),
                    Importance.HIGH, "caminho relativo à API");
        }
        @Override public Condition configure(MockConfig c) {
            PathTemplate t = PathTemplate.of(c.getString("pattern"));
            return (req, ctx) -> t.matches(req.path());
        }
        @Override public StaticForm staticForm(MockConfig c) {
            String pattern = c.getString("pattern");
            PathTemplate t = PathTemplate.of(pattern);
            return new StaticForm.OnStub(stub -> stub.request().path().equals(pattern)
                    || t.matches(stub.request().template().samplePath()));
        }
    }

    /** {@code method-is}: só para certos métodos HTTP. */
    public static final class MethodIs implements RequestPredicate {
        @Override public String name() { return "method-is"; }
        @Override public String description() { return "Aplica a transformação só para os métodos listados (ex.: POST,PUT)."; }
        @Override public ConfigDef config() {
            return new ConfigDef().define("methods", Type.LIST, ConfigDef.NO_DEFAULT, Validators.nonEmpty(),
                    Importance.HIGH, "métodos separados por vírgula", "GET", "POST", "PUT", "PATCH", "DELETE");
        }
        @Override public Condition configure(MockConfig c) {
            List<String> methods = c.getList("methods").stream().map(m -> m.toUpperCase(Locale.ROOT)).toList();
            return (req, ctx) -> methods.contains(req.method());
        }
        @Override public StaticForm staticForm(MockConfig c) {
            List<String> methods = c.getList("methods").stream().map(m -> m.toUpperCase(Locale.ROOT)).toList();
            return new StaticForm.OnStub(stub -> stub.request().method() == null || methods.contains(stub.request().method()));
        }
    }

    /**
     * {@code header-matches}: por cabeçalho — o jeito de escolher a variação POR REQUISIÇÃO
     * (ex.: {@code X-Mock-Variation: negado}) sem mexer no mock entre um teste e outro.
     */
    public static final class HeaderMatches implements RequestPredicate {
        @Override public String name() { return "header-matches"; }
        @Override public String description() {
            return "Aplica quando um cabeçalho casa com a regex — ex.: X-Mock-Variation: negado escolhe a variação por requisição.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("name", Type.STRING, ConfigDef.NO_DEFAULT, Validators.nonEmpty(), Importance.HIGH, "nome do cabeçalho")
                    .define("regex", Type.STRING, ".+", Validators.regex(), Importance.MEDIUM, "regex do valor (padrão: presente)");
        }
        @Override public Condition configure(MockConfig c) {
            String name = c.getString("name");
            Pattern rx = Pattern.compile(c.getString("regex"));
            return (req, ctx) -> {
                String v = req.header(name);
                return v != null && rx.matcher(v).matches();
            };
        }
        @Override public StaticForm staticForm(MockConfig c) {
            return new StaticForm.ExtraConstraint(new RequestMatcher.Constraint(
                    RequestMatcher.Constraint.Kind.HEADER, c.getString("name"), c.getString("regex")));
        }
    }

    /** {@code body-matches}: por campo do corpo da requisição (ex.: valor acima do limite). */
    public static final class BodyMatches implements RequestPredicate {
        @Override public String name() { return "body-matches"; }
        @Override public String description() {
            return "Aplica quando um campo do JSON da requisição casa com a regex — ex.: /amount ^[0-9]{5,}.* (valores altos).";
        }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("pointer", Type.STRING, ConfigDef.NO_DEFAULT, Validators.jsonPointer(), Importance.HIGH, "campo (JSON Pointer)")
                    .define("regex", Type.STRING, ConfigDef.NO_DEFAULT, Validators.regex(), Importance.HIGH, "regex do valor");
        }
        @Override public Condition configure(MockConfig c) {
            String pointer = c.getString("pointer");
            Pattern rx = Pattern.compile(c.getString("regex"));
            return (req, ctx) -> {
                JsonNode body = req.bodyJson();
                JsonNode at = body == null ? null : body.at(pointer);
                return at != null && !at.isMissingNode() && rx.matcher(at.isValueNode() ? at.asText() : at.toString()).matches();
            };
        }
        @Override public StaticForm staticForm(MockConfig c) {
            return new StaticForm.ExtraConstraint(new RequestMatcher.Constraint(
                    RequestMatcher.Constraint.Kind.BODY, c.getString("pointer"), c.getString("regex")));
        }
    }

    /**
     * {@code call-count}: n-ésima chamada ao stub — {@code 1}, {@code 1-2}, {@code 3+}, {@code every:3}.
     * Ex.: 503 nas 2 primeiras e 200 depois = valida o retry com backoff.
     */
    public static final class CallCount implements RequestPredicate {
        @Override public String name() { return "call-count"; }
        @Override public String description() {
            return "Aplica na n-ésima chamada (1, 1-2, 3+, every:3) — ex.: falha nas 2 primeiras para validar retry.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef().define("calls", Type.STRING, ConfigDef.NO_DEFAULT, (name, value) -> parse(name, String.valueOf(value)),
                    Importance.HIGH, "faixa de chamadas", "1", "1-2", "3+", "every:3");
        }
        @Override public Condition configure(MockConfig c) {
            long[] r = parse("calls", c.getString("calls"));
            return (req, ctx) -> r[2] > 0 ? ctx.callNumber() % r[2] == 0 : ctx.callNumber() >= r[0] && ctx.callNumber() <= r[1];
        }

        /** [min, max, every]. */
        static long[] parse(String name, String spec) {
            String s = spec.trim();
            try {
                if (s.startsWith("every:")) {
                    long every = Long.parseLong(s.substring(6));
                    if (every < 1) {
                        throw new NumberFormatException();
                    }
                    return new long[] {0, 0, every};
                }
                if (s.endsWith("+")) {
                    return new long[] {Long.parseLong(s.substring(0, s.length() - 1)), Long.MAX_VALUE, 0};
                }
                int dash = s.indexOf('-');
                if (dash > 0) {
                    long a = Long.parseLong(s.substring(0, dash));
                    long b = Long.parseLong(s.substring(dash + 1));
                    if (a < 1 || b < a) {
                        throw new NumberFormatException();
                    }
                    return new long[] {a, b, 0};
                }
                long n = Long.parseLong(s);
                if (n < 1) {
                    throw new NumberFormatException();
                }
                return new long[] {n, n, 0};
            } catch (NumberFormatException e) {
                throw new ConfigException(name, "use 1, 1-2, 3+ ou every:3 (contagem começa em 1)");
            }
        }
    }

    /** {@code probability}: caos controlado — aplica com probabilidade p (semente opcional = reproduzível). */
    public static final class Probability implements RequestPredicate {
        @Override public String name() { return "probability"; }
        @Override public String description() {
            return "Aplica com probabilidade p (0..1) — caos controlado; use seed para reproduzir a mesma sequência.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("p", Type.DOUBLE, ConfigDef.NO_DEFAULT, Validators.probability(), Importance.HIGH, "probabilidade", "0.1", "0.3", "0.5")
                    .define("seed", Type.LONG, -1L, null, Importance.LOW, "semente (-1 = aleatória)");
        }
        @Override public Condition configure(MockConfig c) {
            double p = c.getDouble("p");
            long seed = c.getLong("seed");
            SplittableRandom rnd = seed >= 0 ? new SplittableRandom(seed) : new SplittableRandom();
            return (req, ctx) -> {
                synchronized (rnd) {
                    return rnd.nextDouble() < p;
                }
            };
        }
    }
}
