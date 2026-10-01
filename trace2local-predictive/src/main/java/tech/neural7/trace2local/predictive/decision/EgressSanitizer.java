package tech.neural7.trace2local.predictive.decision;

import tech.neural7.trace2local.internal.TextRedactor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Última barreira antes do egresso para o Jev (ADR-011): todo valor de estado
 * passa pelo {@link TextRedactor} (segredos, e-mail, documento, cartão, token),
 * é truncado, e — no egresso {@code structural} — HOSTS de URL são trocados por
 * pseudônimos estáveis DENTRO da requisição ({@code https://<host-1>/v1}): o
 * modelo ainda percebe "mesmo host × host diferente" sem conhecer a rede da
 * empresa. IPs privados idem.
 */
final class EgressSanitizer {

    private static final Pattern URL_HOST = Pattern.compile("(?i)\\b(https?://)([^/\\s:?#\"']+)");
    private static final Pattern PRIVATE_IP = Pattern.compile("\\b(10\\.\\d{1,3}|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final int MAX_VALUE = 6000;

    private EgressSanitizer() {}

    static Map<String, String> sanitize(Map<String, String> state, IntelligenceConfig.Egress egress) {
        Map<String, String> hosts = new HashMap<>();
        Map<String, String> out = new LinkedHashMap<>();
        state.forEach((k, v) -> {
            String value = v == null ? "" : v;
            if (value.length() > MAX_VALUE) {
                value = value.substring(0, MAX_VALUE) + "…";
            }
            value = TextRedactor.redact(value);
            if (egress == IntelligenceConfig.Egress.STRUCTURAL) {
                value = pseudonymizeHosts(value, hosts);
                value = PRIVATE_IP.matcher(value).replaceAll("<ip-privado>");
            }
            out.put(k, value);
        });
        return out;
    }

    private static String pseudonymizeHosts(String text, Map<String, String> hosts) {
        Matcher m = URL_HOST.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String host = m.group(2).toLowerCase(java.util.Locale.ROOT);
            String alias = host.equals("localhost") || host.startsWith("127.") || host.equals("localstack")
                    ? host : hosts.computeIfAbsent(host, h -> "<host-" + (hosts.size() + 1) + ">");
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + alias));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
