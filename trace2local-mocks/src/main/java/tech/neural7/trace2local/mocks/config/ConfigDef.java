package tech.neural7.trace2local.mocks.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import tech.neural7.trace2local.internal.JsonSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Definição declarativa da configuração de um plugin — o mesmo contrato do
 * {@code ConfigDef} do Kafka Connect: cada chave tem tipo, padrão, validador,
 * importância, grupo e documentação. A UI e a API de validação
 * ({@code PUT /api/mocks/plugins/{nome}/config/validate}) são geradas daqui;
 * nenhum plugin valida "na mão".
 */
public final class ConfigDef {

    /** Tipos aceitos. Valores chegam como texto (config plana) ou já tipados (JSON). */
    public enum Type { STRING, INT, LONG, DOUBLE, BOOLEAN, LIST, JSON, PASSWORD }

    public enum Importance { HIGH, MEDIUM, LOW }

    /** Marcador de "sem padrão": a chave é obrigatória. */
    public static final Object NO_DEFAULT = new Object() {
        @Override
        public String toString() {
            return "<obrigatório>";
        }
    };

    /** Validador de valor já convertido para o tipo. Lança {@link ConfigException} com mensagem para humanos. */
    @FunctionalInterface
    public interface Validator {
        void ensureValid(String name, Object value);

        /** Texto curto exibido na documentação (ex.: "[100..599]"). */
        default String describe() {
            return "";
        }
    }

    /** Definição de uma chave. */
    public record Key(String name, Type type, Object defaultValue, Validator validator, Importance importance,
                      String documentation, String group, List<String> recommendedValues) {
        public boolean required() {
            return defaultValue == NO_DEFAULT;
        }
    }

    private final Map<String, Key> keys = new LinkedHashMap<>();
    private String currentGroup = "Geral";

    /** As próximas chaves pertencem a este grupo (seção na UI). */
    public ConfigDef group(String group) {
        this.currentGroup = Objects.requireNonNull(group);
        return this;
    }

    public ConfigDef define(String name, Type type, Object defaultValue, Validator validator,
                            Importance importance, String documentation, String... recommended) {
        if (keys.containsKey(name)) {
            throw new IllegalArgumentException("chave definida duas vezes: " + name);
        }
        Object parsedDefault = defaultValue == NO_DEFAULT || defaultValue == null
                ? defaultValue : parseValue(name, type, defaultValue);
        keys.put(name, new Key(name, type, parsedDefault, validator, importance, documentation, currentGroup,
                recommended == null ? List.of() : List.of(recommended)));
        return this;
    }

    /** Chave obrigatória sem validador extra. */
    public ConfigDef define(String name, Type type, Importance importance, String documentation) {
        return define(name, type, NO_DEFAULT, null, importance, documentation);
    }

    public Map<String, Key> keys() {
        return Collections.unmodifiableMap(keys);
    }

    /**
     * Interpreta e valida. Agrega TODOS os erros (o dev corrige de uma vez, não um por tentativa).
     *
     * @throws ConfigException com a lista de erros por chave
     */
    public MockConfig parse(Map<String, ?> props) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Checked c : check(props)) {
            if (!c.shown().errors().isEmpty()) {
                errors.put(c.shown().name(), c.shown().errors());
            } else if (keys.containsKey(c.shown().name())) {
                parsed.put(c.shown().name(), c.actual());
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return new MockConfig(parsed, props == null ? Map.of() : props);
    }

    /**
     * Validação no formato do Kafka Connect: um {@link ConfigValue} por chave definida,
     * mais um por chave DESCONHECIDA (com sugestão da chave mais próxima — erro de
     * digitação é a falha de configuração mais comum).
     */
    public List<ConfigValue> validate(Map<String, ?> props) {
        return check(props).stream().map(Checked::shown).toList();
    }

    /** Valor exibido (PASSWORD mascarado) + valor real (só para {@link #parse}). */
    private record Checked(ConfigValue shown, Object actual) {}

    private List<Checked> check(Map<String, ?> props) {
        Map<String, ?> in = props == null ? Map.of() : props;
        List<Checked> out = new ArrayList<>();
        for (Key key : keys.values()) {
            List<String> errors = new ArrayList<>();
            Object raw = in.get(key.name());
            Object value = null;
            boolean blank = raw == null || (raw instanceof String s && s.isBlank());
            if (blank) {
                if (key.required()) {
                    errors.add("obrigatório: " + key.documentation());
                } else {
                    value = key.defaultValue();
                }
            } else {
                try {
                    value = parseValue(key.name(), key.type(), raw);
                } catch (ConfigException e) {
                    errors.addAll(e.messages());
                }
            }
            if (errors.isEmpty() && value != null && key.validator() != null) {
                try {
                    key.validator().ensureValid(key.name(), value);
                } catch (ConfigException e) {
                    errors.addAll(e.messages());
                }
            }
            boolean secret = key.type() == Type.PASSWORD && (value != null || raw != null);
            Object shown = secret ? Password.HIDDEN : value;
            out.add(new Checked(new ConfigValue(key.name(), shown, key.recommendedValues(), errors, true), value));
        }
        for (String name : in.keySet()) {
            if (!keys.containsKey(name)) {
                String hint = closest(name);
                Object shownUnknown = tech.neural7.trace2local.internal.Redactor.isSensitiveKey(name) ? Password.HIDDEN : in.get(name);
                out.add(new Checked(new ConfigValue(name, shownUnknown, List.of(),
                        List.of("chave desconhecida" + (hint == null ? "" : " — você quis dizer '" + hint + "'?")), true), null));
            }
        }
        return out;
    }

    static Object parseValue(String name, Type type, Object raw) {
        try {
            return switch (type) {
                case STRING -> raw instanceof JsonNode n && n.isTextual() ? n.asText() : String.valueOf(raw).trim();
                case INT -> raw instanceof Number n ? n.intValue() : Integer.parseInt(text(raw));
                case LONG -> raw instanceof Number n ? n.longValue() : Long.parseLong(text(raw));
                case DOUBLE -> raw instanceof Number n ? n.doubleValue() : Double.parseDouble(text(raw));
                case BOOLEAN -> {
                    if (raw instanceof Boolean b) {
                        yield b;
                    }
                    String t = text(raw).toLowerCase(Locale.ROOT);
                    if (!t.equals("true") && !t.equals("false")) {
                        throw new ConfigException(name, "esperado true ou false, recebido '" + t + "'");
                    }
                    yield Boolean.parseBoolean(t);
                }
                case LIST -> {
                    if (raw instanceof List<?> l) {
                        yield l.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList();
                    }
                    yield Arrays.stream(text(raw).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                }
                case JSON -> json(raw);
                case PASSWORD -> raw instanceof Password p ? p : new Password(text(raw));
            };
        } catch (NumberFormatException e) {
            throw new ConfigException(name, "valor numérico inválido: '" + raw + "'");
        }
    }

    /**
     * JSON tolerante: {@code DENIED} vira o texto "DENIED", {@code 42} vira número, {@code null}
     * vira null e {@code {"a":1}} vira objeto. Para forçar o texto "42", use {@code "\"42\""}.
     */
    private static JsonNode json(Object raw) {
        if (raw instanceof JsonNode n) {
            return n;
        }
        if (!(raw instanceof String s)) {
            return JsonSupport.MAPPER.valueToTree(raw);
        }
        String t = s.trim();
        try {
            JsonNode parsed = JsonSupport.MAPPER.readTree(t);
            return parsed == null || parsed.isMissingNode() ? TextNode.valueOf(s) : parsed;
        } catch (Exception notJson) {
            return TextNode.valueOf(s);
        }
    }

    private static String text(Object raw) {
        if (raw instanceof JsonNode n) {
            return n.isTextual() ? n.asText().trim() : n.toString();
        }
        return String.valueOf(raw).trim();
    }

    private String closest(String name) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : keys.keySet()) {
            int d = levenshtein(name, candidate);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best != null && bestDistance <= Math.max(2, best.length() / 3) ? best : null;
    }

    /** Distância de edição (sugestão de chave, near-miss de caminho). */
    public static int levenshteinDistance(String a, String b) {
        return levenshtein(a == null ? "" : a, b == null ? "" : b);
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
