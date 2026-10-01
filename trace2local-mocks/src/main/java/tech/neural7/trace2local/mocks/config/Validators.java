package tech.neural7.trace2local.mocks.config;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validadores reutilizáveis (equivalentes a {@code ConfigDef.Range}/{@code ValidString} do Kafka). */
public final class Validators {

    private Validators() {}

    public static ConfigDef.Validator range(long min, long max) {
        return new ConfigDef.Validator() {
            @Override
            public void ensureValid(String name, Object value) {
                double v = ((Number) value).doubleValue();
                if (v < min || v > max) {
                    throw new ConfigException(name, "fora do intervalo [" + min + ".." + max + "]: " + value);
                }
            }

            @Override
            public String describe() {
                return "[" + min + ".." + max + "]";
            }
        };
    }

    public static ConfigDef.Validator probability() {
        return new ConfigDef.Validator() {
            @Override
            public void ensureValid(String name, Object value) {
                double v = ((Number) value).doubleValue();
                if (v < 0 || v > 1) {
                    throw new ConfigException(name, "probabilidade deve estar entre 0 e 1: " + value);
                }
            }

            @Override
            public String describe() {
                return "[0..1]";
            }
        };
    }

    public static ConfigDef.Validator in(String... allowed) {
        List<String> list = List.of(allowed);
        return new ConfigDef.Validator() {
            @Override
            public void ensureValid(String name, Object value) {
                if (value instanceof List<?> l) {
                    for (Object o : l) {
                        check(name, String.valueOf(o));
                    }
                } else {
                    check(name, String.valueOf(value));
                }
            }

            private void check(String name, String v) {
                if (!list.contains(v.toLowerCase(Locale.ROOT)) && !list.contains(v)) {
                    throw new ConfigException(name, "valor '" + v + "' não é um de " + list);
                }
            }

            @Override
            public String describe() {
                return String.join(" | ", list);
            }
        };
    }

    public static ConfigDef.Validator nonEmpty() {
        return (name, value) -> {
            if (value instanceof String s && s.isBlank() || value instanceof List<?> l && l.isEmpty()) {
                throw new ConfigException(name, "não pode ser vazio");
            }
        };
    }

    public static ConfigDef.Validator regex() {
        return (name, value) -> {
            try {
                Pattern.compile(String.valueOf(value));
            } catch (PatternSyntaxException e) {
                throw new ConfigException(name, "expressão regular inválida: " + e.getDescription());
            }
        };
    }

    /** JSON Pointer (RFC 6901): vazio = documento inteiro, senão começa com "/". */
    public static ConfigDef.Validator jsonPointer() {
        return new ConfigDef.Validator() {
            @Override
            public void ensureValid(String name, Object value) {
                String v = String.valueOf(value);
                if (!v.isEmpty() && !v.startsWith("/")) {
                    throw new ConfigException(name, "JSON Pointer deve começar com '/' (ex.: /decision, /account/limit)");
                }
            }

            @Override
            public String describe() {
                return "JSON Pointer (RFC 6901)";
            }
        };
    }

    /** host ou host:porta (o alvo que o mock substitui). */
    public static ConfigDef.Validator hostAndPort() {
        return (name, value) -> {
            String v = String.valueOf(value);
            if (v.contains("/") || v.contains("@") || v.isBlank() || v.contains(" ")) {
                throw new ConfigException(name, "use host ou host:porta (ex.: antifraude.parceiro:8080), sem esquema nem caminho");
            }
            int colon = v.lastIndexOf(':');
            if (colon > 0) {
                try {
                    int port = Integer.parseInt(v.substring(colon + 1));
                    if (port < 1 || port > 65535) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw new ConfigException(name, "porta inválida em '" + v + "'");
                }
            }
        };
    }

    /**
     * URL http(s) sem credenciais. {@code allowPublic=false} restringe a loopback/rede privada
     * (o Station não vira ponte para hosts arbitrários — anti-SSRF).
     */
    public static ConfigDef.Validator httpUrl(boolean allowPublic) {
        return (name, value) -> {
            URI uri;
            try {
                uri = URI.create(String.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(name, "URL inválida: " + value);
            }
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                    || uri.getHost() == null) {
                throw new ConfigException(name, "use http:// ou https:// com host");
            }
            if (uri.getUserInfo() != null) {
                throw new ConfigException(name, "não coloque credenciais na URL — use a chave de token (PASSWORD)");
            }
            if (!allowPublic && !isPrivateHost(uri.getHost())) {
                throw new ConfigException(name, "host público recusado: '" + uri.getHost()
                        + "' (o destino de mock deve ser local/rede privada; libere com TRACE2LOCAL_MOCKS_ALLOW_PUBLIC_SINKS=true)");
            }
        };
    }

    /**
     * Loopback, rede privada (RFC 1918/ULA), link-local ou nome sem domínio público
     * (nomes de serviço do docker-compose: "wiremock", "localstack"...).
     */
    public static boolean isPrivateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".internal") || h.endsWith(".local")
                || h.endsWith(".svc") || h.endsWith(".svc.cluster.local") || !h.contains(".")) {
            return true;
        }
        if (h.matches("[0-9.]+") || h.contains(":")) {
            try {
                InetAddress a = InetAddress.getByName(h); // literal: não resolve DNS
                return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xFE) == 0xFC);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
