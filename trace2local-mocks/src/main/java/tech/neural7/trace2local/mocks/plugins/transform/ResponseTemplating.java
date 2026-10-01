package tech.neural7.trace2local.mocks.plugins.transform;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.mocks.model.MockRequest;
import tech.neural7.trace2local.mocks.model.MockResponse;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mini-templating de resposta (sem expressão arbitrária — só leitura de campos). */
public final class ResponseTemplating {

    private static final Pattern MARK = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");

    private ResponseTemplating() {}

    public static String render(String template, MockRequest req, MockResponse res) {
        Matcher m = MARK.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = resolve(m.group(1), req, res);
            boolean insideJsonString = insideString(template, m.start());
            String safe = value == null ? (insideJsonString ? "" : "null") : insideJsonString ? escapeJson(value) : value;
            m.appendReplacement(sb, Matcher.quoteReplacement(safe));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String resolve(String expr, MockRequest req, MockResponse res) {
        if (expr.equals("uuid")) {
            return UUID.randomUUID().toString();
        }
        if (expr.equals("now")) {
            return Instant.now().toString();
        }
        if (expr.equals("request.method")) {
            return req.method();
        }
        if (expr.equals("request.path")) {
            return req.path();
        }
        if (expr.startsWith("request.path.")) {
            String[] segs = req.path().split("/");
            try {
                int n = Integer.parseInt(expr.substring("request.path.".length()));
                return n >= 0 && n + 1 < segs.length ? segs[n + 1] : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (expr.startsWith("request.query.")) {
            return req.queryParam(expr.substring("request.query.".length()));
        }
        if (expr.startsWith("request.header.")) {
            return req.header(expr.substring("request.header.".length()));
        }
        if (expr.startsWith("request.body")) {
            return text(req.bodyJson(), expr.substring("request.body".length()));
        }
        if (expr.startsWith("response")) {
            return text(res.bodyJson(), expr.substring("response".length()));
        }
        return null;
    }

    private static String text(JsonNode node, String pointer) {
        if (node == null) {
            return null;
        }
        JsonNode at = node.at(pointer);
        return at.isMissingNode() || at.isNull() ? null : at.isValueNode() ? at.asText() : at.toString();
    }

    /** O marcador está dentro de uma string JSON? (conta aspas não escapadas antes dele) */
    private static boolean insideString(String s, int pos) {
        boolean inside = false;
        for (int i = 0; i < pos; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                inside = !inside;
            }
        }
        return inside;
    }

    private static String escapeJson(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
