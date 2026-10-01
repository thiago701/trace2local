package tech.neural7.trace2local.mocks.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Caminho com variáveis — {@code /entries/{key}} (OpenAPI), {@code *} (um segmento),
 * {@code **} (qualquer sufixo) ou {@code re:<regex>}. Especificidade = segmentos
 * literais; desempata stubs com a mesma prioridade (o mais específico vence).
 */
public final class PathTemplate {

    private final String template;
    private final Pattern pattern;
    private final List<String> variables;
    private final int literalSegments;

    private PathTemplate(String template, Pattern pattern, List<String> variables, int literalSegments) {
        this.template = template;
        this.pattern = pattern;
        this.variables = variables;
        this.literalSegments = literalSegments;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, PathTemplate> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static PathTemplate of(String template) {
        String key = template == null ? "" : template;
        PathTemplate cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        PathTemplate compiled = compile(template);
        if (CACHE.size() < 4096) { // templates vêm de config: conjunto pequeno e estável
            CACHE.put(key, compiled);
        }
        return compiled;
    }

    private static PathTemplate compile(String template) {
        String t = template == null || template.isBlank() ? "/**" : template.trim();
        if (t.startsWith("re:")) {
            return new PathTemplate(t, Pattern.compile(t.substring(3)), List.of(), 0);
        }
        if (!t.startsWith("/")) {
            t = "/" + t;
        }
        StringBuilder rx = new StringBuilder("^");
        List<String> vars = new ArrayList<>();
        int literals = 0;
        String[] segments = t.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.equals("**")) {
                rx.append("(?:/.*)?");
                continue;
            }
            rx.append('/');
            if (seg.equals("*")) {
                rx.append("[^/]+");
            } else if (seg.startsWith("{") && seg.endsWith("}")) {
                vars.add(seg.substring(1, seg.length() - 1));
                rx.append("([^/]+)");
            } else {
                literals++;
                rx.append(Pattern.quote(seg));
            }
        }
        rx.append("/?$");
        return new PathTemplate(t, Pattern.compile(rx.toString()), List.copyOf(vars), literals);
    }

    public boolean matches(String path) {
        return pattern.matcher(path == null ? "/" : path).matches();
    }

    /** Variáveis do caminho ({@code {key}} → valor), ou mapa vazio se não casar. */
    public Map<String, String> extract(String path) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = pattern.matcher(path == null ? "/" : path);
        if (m.matches()) {
            for (int i = 0; i < variables.size() && i < m.groupCount(); i++) {
                out.put(variables.get(i), m.group(i + 1));
            }
        }
        return out;
    }

    /** Um caminho de exemplo que casa com o template ({@code {id}} → "sample"). */
    public String samplePath() {
        if (template.startsWith("re:")) {
            return "/";
        }
        return template.replaceAll("\\{[^}/]+}", "sample").replace("/**", "").replace("*", "sample");
    }

    public int specificity() {
        return literalSegments;
    }

    public String template() {
        return template;
    }

    @Override
    public String toString() {
        return template;
    }
}
