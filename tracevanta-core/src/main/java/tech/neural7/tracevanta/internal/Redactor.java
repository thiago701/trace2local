package tech.neural7.tracevanta.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import tech.neural7.tracevanta.config.RedactionMode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redaction aplicada NA ORIGEM, antes de o dado entrar no buffer — nunca na UI
 * (ADR-007 / SPEC §8.3). Substituto literal: {@code [TRACEVANTA_REDACTED]}.
 *
 * <p>Três camadas: (1) por chave sensível, (2) por padrão de valor (STRICT),
 * (3) truncamento por tamanho. A política é mitigação, não garantia — o limite
 * declarado está no README.
 */
public final class Redactor {

    public static final String REDACTED = "[TRACEVANTA_REDACTED]";
    public static final String TRUNCATED = "[TRACEVANTA_TRUNCATED]";

    /** Limite de caracteres por valor de string antes do truncamento. */
    private static final int MAX_STRING_CHARS = 1024;
    /** Limite de bytes do payload serializado (ajustado pela config payload.max-bytes no caller). */
    static final int DEFAULT_MAX_PAYLOAD_BYTES = 8192;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "senha", "secret", "token", "authorization", "apikey",
            "api_key", "accesskey", "access_key", "secretkey", "secret_key", "sessiontoken",
            "session_token", "credential", "credentials", "cpf", "cnpj", "card", "cvv",
            "pin", "ssn", "bearer", "cookie", "set-cookie", "x-api-key", "x-auth-token",
            "jwt", "otp", "totp", "privatekey", "private_key");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern JWT = Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final Pattern AWS_KEY = Pattern.compile("^(AKIA|ASIA|AIDA|AROA|AIPA|ANPA|ANVA)[A-Z0-9]{16}$");
    private static final Pattern PEM = Pattern.compile("-----BEGIN [A-Z0-9 ]+PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("(?i)^Bearer\\s+\\S+$");
    private static final Pattern CPF = Pattern.compile("^\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}$");
    private static final Pattern CNPJ = Pattern.compile("^\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}$");
    private static final Pattern CARD_DIGITS = Pattern.compile("^[0-9]{13,19}$");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("^gh[pousr]_[A-Za-z0-9]{36,}$");
    private static final Pattern PASSWORD_HASH = Pattern.compile("^\\$2[abxy]\\$|^\\$argon2");

    private Redactor() {}

    /** Redige recursivamente um documento JSON. Devolve uma cópia; nunca muta a entrada. */
    public static JsonNode redactJson(JsonNode input, RedactionMode mode) {
        if (input == null) {
            return null;
        }
        return switch (mode) {
            case OFF -> input;
            case KEYS, STRICT -> walk(input, mode, "");
        };
    }

    private static JsonNode walk(JsonNode node, RedactionMode mode, String path) {
        if (node.isObject()) {
            ObjectNode out = JsonSupport.MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode value = e.getValue();
                if (isSensitiveKey(key)) {
                    out.set(key, TextNode.valueOf(REDACTED));
                } else if (value.isObject() || value.isArray()) {
                    out.set(key, walk(value, mode, path + "/" + key));
                } else if (value.isTextual()) {
                    out.set(key, TextNode.valueOf(redactString(value.asText(), mode)));
                } else {
                    out.set(key, value);
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonSupport.MAPPER.createArrayNode();
            for (JsonNode child : node) {
                if (child.isObject() || child.isArray()) {
                    out.add(walk(child, mode, path));
                } else if (child.isTextual()) {
                    out.add(TextNode.valueOf(redactString(child.asText(), mode)));
                } else {
                    out.add(child);
                }
            }
            return out;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(redactString(node.asText(), mode));
        }
        return node;
    }

    /** Redige um valor textual isolado (por padrão de valor) e aplica truncamento. */
    public static String redactString(String value, RedactionMode mode) {
        if (value == null) {
            return null;
        }
        String v = value;
        if (mode == RedactionMode.STRICT && matchesSensitivePattern(v)) {
            v = REDACTED;
        }
        if (v.length() > MAX_STRING_CHARS) {
            v = v.substring(0, MAX_STRING_CHARS) + TRUNCATED;
        }
        return v;
    }

    /** Decide por chave; o {@code key} já pode vir redigido. */
    public static boolean isSensitiveKey(String key) {
        String k = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (String sensitive : SENSITIVE_KEYS) {
            if (k.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesSensitivePattern(String value) {
        if (value.isBlank()) {
            return false;
        }
        String v = value.trim();
        if (EMAIL.matcher(v).matches()) return true;
        if (JWT.matcher(v).matches()) return true;
        if (AWS_KEY.matcher(v).matches()) return true;
        if (PEM.matcher(v).find()) return true;
        if (BEARER.matcher(v).matches()) return true;
        if (GITHUB_TOKEN.matcher(v).matches()) return true;
        if (PASSWORD_HASH.matcher(v).find()) return true;
        if (CPF.matcher(v).matches() || CNPJ.matcher(v).matches()) return true;
        if (CARD_DIGITS.matcher(v.replaceAll("[\\s-]", "")).matches()) {
            return luhnValid(v.replaceAll("[\\s-]", ""));
        }
        return false;
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    /**
     * Cap de tamanho do payload JSON serializado. Quando estoura o orçamento, o maior
     * prefixo que ainda é JSON válido é preservado e envolvido com a marca de
     * truncamento — a UI detecta {@code _truncated} e exibe o aviso honesto.
     */
    public static JsonNode capPayload(JsonNode node, int maxBytes) {
        String serialized = JsonSupport.write(node);
        if (serialized == null) {
            return node;
        }
        if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
            return node;
        }
        int budget = Math.max(64, maxBytes - 96); // reserva para o wrapper
        String prefix = truncateToValidJson(serialized, budget);
        ObjectNode out = JsonSupport.MAPPER.createObjectNode();
        out.put("_truncated", TRUNCATED);
        out.put("_truncatedBytes", maxBytes);
        out.set("payload", JsonSupport.parse(prefix));
        return out;
    }

    private static String truncateToValidJson(String json, int maxChars) {
        if (json.length() <= maxChars) {
            return json;
        }
        String s = json.substring(0, maxChars);
        for (int i = s.length(); i > 0; i--) {
            try {
                JsonSupport.MAPPER.readTree(s);
                return s;
            } catch (Exception ignored) {
                s = s.substring(0, i - 1);
            }
        }
        return "{}";
    }

    /** Redige, trunca e serializa um payload para entrar no TVEM (usado pelos módulos de ponte). */
    public static String payloadToJson(JsonNode node, int maxBytes, RedactionMode mode) {
        if (node == null) {
            return null;
        }
        JsonNode redacted = redactJson(node, mode);
        JsonNode capped = capPayload(redacted, maxBytes);
        return JsonSupport.write(capped);
    }
}
