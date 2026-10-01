package tech.neural7.trace2local.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redação de TEXTO LIVRE (linhas de log, mensagens de erro enviadas a modelos de
 * decisão). O {@link Redactor} decide por valor isolado ({@code ^…$}); aqui os
 * padrões são procurados DENTRO do texto e substituídos in-place, preservando o
 * resto da linha para que ela continue legível.
 *
 * <p>Cobertura: {@code chave=valor}/{@code "chave":"valor"} com chave sensível
 * (mesmo critério de {@link Redactor#isSensitiveKey}), e-mail, JWT, chave AWS,
 * {@code Bearer …}, CPF/CNPJ formatados, cartão (Luhn) e userinfo em URL
 * ({@code https://user:pass@host}). É mitigação, não garantia (SPEC §8.3).
 */
public final class TextRedactor {

    private static final String R = Redactor.REDACTED;

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)([\"']?)([A-Za-z0-9_.\\-]{2,40})\\1(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\]]+)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
    private static final Pattern AWS_KEY = Pattern.compile("\\b(AKIA|ASIA|AIDA|AROA|AIPA|ANPA|ANVA)[A-Z0-9]{16}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=\\-]{8,}");
    private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b");
    private static final Pattern CNPJ = Pattern.compile("\\b\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}\\b");
    /**
     * Cartão: formatado em grupos de 4 (13–19 dígitos) ou corrido com 15–16 dígitos.
     * Sequências de 13 dígitos corridos ficam de fora de propósito: são epoch-millis,
     * onipresentes em log — o Luhn sozinho deixaria ~10% delas redigidas à toa.
     */
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d{4}[ -]){3}\\d{1,7}\\b|\\b\\d{15,16}\\b");
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)\\b([a-z][a-z0-9+.\\-]*://)([^/\\s:@]+):([^/\\s@]+)@");
    private static final Pattern API_KEY_LITERAL = Pattern.compile("\\bapikey_[A-Za-z0-9_]{16,}\\b");

    private static final int MAX_CHARS = 4096;

    private TextRedactor() {}

    /** Redige padrões sensíveis dentro do texto; {@code null} devolve {@code null}. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String t = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + Redactor.TRUNCATED : text;
        t = URL_USERINFO.matcher(t).replaceAll("$1" + R + "@");
        t = replaceSensitiveKeyValues(t);
        t = JWT.matcher(t).replaceAll(R);
        t = BEARER.matcher(t).replaceAll("Bearer " + R);
        t = AWS_KEY.matcher(t).replaceAll(R);
        t = API_KEY_LITERAL.matcher(t).replaceAll(R);
        t = EMAIL.matcher(t).replaceAll(R);
        t = CPF.matcher(t).replaceAll(R);
        t = CNPJ.matcher(t).replaceAll(R);
        t = replaceCards(t);
        return t;
    }

    private static String replaceSensitiveKeyValues(String t) {
        Matcher m = KEY_VALUE.matcher(t);
        StringBuilder sb = new StringBuilder(t.length());
        while (m.find()) {
            String key = m.group(2);
            if (Redactor.isSensitiveKey(key)) {
                String value = m.group(4);
                String quote = value.startsWith("\"") ? "\"" : value.startsWith("'") ? "'" : "";
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        m.group(1) + key + m.group(1) + m.group(3) + quote + R + quote));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceCards(String t) {
        Matcher m = CARD.matcher(t);
        StringBuilder sb = new StringBuilder(t.length());
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            boolean card = digits.length() >= 13 && digits.length() <= 19 && Redactor.luhnValid(digits);
            m.appendReplacement(sb, Matcher.quoteReplacement(card ? R : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
