package tech.neural7.trace2local.mocks.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Critério de casamento de um stub: método, caminho (template), query exata e
 * restrições extras (cabeçalho ou campo do corpo por regex).
 */
public record RequestMatcher(String method, String path, Map<String, String> query, List<Constraint> constraints) {

    /** Restrição adicional — {@code HEADER} (nome) ou {@code BODY} (JSON Pointer), valor por regex. */
    public record Constraint(Kind kind, String key, String regex) {
        public enum Kind { HEADER, BODY }

        boolean test(MockRequest r) {
            String actual = switch (kind) {
                case HEADER -> r.header(key);
                case BODY -> {
                    JsonNode body = r.bodyJson();
                    JsonNode at = body == null ? null : body.at(key);
                    yield at == null || at.isMissingNode() ? null : at.isValueNode() ? at.asText() : at.toString();
                }
            };
            return actual != null && Pattern.compile(regex).matcher(actual).matches();
        }
    }

    public RequestMatcher {
        method = method == null || method.isBlank() || method.equals("*") ? null : method.toUpperCase(Locale.ROOT);
        path = path == null || path.isBlank() ? "/**" : path;
        query = query == null ? Map.of() : Map.copyOf(query);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    public static RequestMatcher of(String method, String path) {
        return new RequestMatcher(method, path, Map.of(), List.of());
    }

    public RequestMatcher with(Constraint constraint) {
        List<Constraint> list = new ArrayList<>(constraints);
        list.add(constraint);
        return new RequestMatcher(method, path, query, list);
    }

    public PathTemplate template() {
        return PathTemplate.of(path);
    }

    public boolean matches(MockRequest r) {
        if (method != null && !method.equals(r.method())) {
            return false;
        }
        if (!template().matches(r.path())) {
            return false;
        }
        for (Map.Entry<String, String> q : query.entrySet()) {
            if (!q.getValue().equals(r.queryParam(q.getKey()))) {
                return false;
            }
        }
        for (Constraint c : constraints) {
            if (!c.test(r)) {
                return false;
            }
        }
        return true;
    }

    /** Especificidade para desempate: segmentos literais + restrições + método fixo. */
    public int specificity() {
        return template().specificity() * 4 + constraints.size() * 2 + query.size() * 2 + (method == null ? 0 : 1);
    }
}
