package tech.neural7.trace2local.server;

import com.sun.net.httpserver.HttpExchange;
import tech.neural7.trace2local.config.Trace2LocalConfig;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Guarda de requisições da UI/API (SPEC §8.1 revisada — prontidão corporativa).
 *
 * <p>"Loopback-only" protege contra a rede, mas NÃO contra o navegador do próprio
 * dev: qualquer página aberta pode (a) fazer POST cross-site para
 * {@code 127.0.0.1:9876/…/api/execute} (CSRF por requisição "simples") e (b) ler a
 * API via <i>DNS rebinding</i> ({@code evil.com → 127.0.0.1}). Três defesas, todas
 * sem autenticação de usuário:
 * <ol>
 *   <li><b>Host allowlist</b> — com bind em loopback, só {@code localhost},
 *       {@code 127.0.0.1}, {@code [::1]} (+ {@code trace2local.allowed-hosts})
 *       são aceitos no cabeçalho {@code Host}: rebinding morre aqui;</li>
 *   <li><b>Mutação exige prova de mesma origem</b> — POST/PUT/DELETE na API
 *       exigem o cabeçalho {@code X-Trace2Local: 1} (não-simples ⇒ preflight CORS
 *       que nunca é aprovado) e, havendo {@code Origin}, ele precisa casar com o
 *       {@code Host};</li>
 *   <li><b>Token de UI opcional</b> ({@code TRACE2LOCAL_UI_TOKEN}) — obrigatório no
 *       perfil {@code corporate} quando o bind sai do loopback (gerado e logado
 *       no boot se ausente, como o Jupyter). Troca por cookie
 *       {@code HttpOnly; SameSite=Strict} na primeira visita.</li>
 * </ol>
 * Ingest (OTLP/mutações/logs) NÃO passa por aqui: tem o próprio Bearer token.
 */
final class RequestGuard {

    private static final Logger LOG = Logger.getLogger(RequestGuard.class.getName());
    static final String CSRF_HEADER = "X-Trace2Local";
    static final String SESSION_COOKIE = "t2l_session";

    /** Decisão do guard: {@code null} = segue; senão status + motivo (sem detalhes internos). */
    record Verdict(int status, String reason) {}

    /** Bind fora do loopback SEM allowlist configurada: qualquer Host é aceito (com WARN). */
    private boolean explicitAnyHost;
    private final Set<String> allowedHosts;
    private final String uiToken;
    private final String sessionValue;
    private final String cookiePath;

    RequestGuard(Trace2LocalConfig cfg) {
        this.allowedHosts = allowedHosts(cfg);
        this.uiToken = resolveUiToken(cfg);
        // o cookie carrega um derivado do token (o token em si nunca volta ao navegador)
        this.sessionValue = uiToken == null ? null : sha256Hex("t2l-session:" + uiToken);
        String base = cfg.basePath() == null || cfg.basePath().isBlank() ? "/" : cfg.basePath();
        this.cookiePath = base;
    }

    boolean tokenRequired() {
        return uiToken != null;
    }

    /** Verifica Host (todas as rotas da UI/API) e, se configurado, o token de UI. */
    Verdict check(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!hostAllowed(host)) {
            return new Verdict(421, "host não permitido (proteção contra DNS rebinding — ver trace2local.allowed-hosts)");
        }
        String method = exchange.getRequestMethod();
        boolean mutating = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        String path = exchange.getRequestURI().getPath();
        boolean api = path.contains("/api/");
        if (mutating && api) {
            String csrf = exchange.getRequestHeaders().getFirst(CSRF_HEADER);
            if (!"1".equals(csrf)) {
                return new Verdict(403, "requisição de mutação sem o cabeçalho " + CSRF_HEADER + " (proteção CSRF)");
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.isBlank() && !"null".equals(origin) && !sameOrigin(origin, host)) {
                return new Verdict(403, "origem cruzada recusada (proteção CSRF)");
            }
        }
        if (uiToken != null && !authenticated(exchange)) {
            return new Verdict(401, "token de UI exigido — abra a URL com ?token=… exibida no log de boot");
        }
        return null;
    }

    /**
     * Troca {@code ?token=} por cookie de sessão. Devolve {@code true} se a
     * resposta (redirect sem o token na URL) já foi enviada.
     */
    boolean exchangeTokenForCookie(HttpExchange exchange) throws java.io.IOException {
        if (uiToken == null) {
            return false;
        }
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || !query.contains("token=")) {
            return false;
        }
        String presented = null;
        for (String pair : query.split("&")) {
            if (pair.startsWith("token=")) {
                presented = java.net.URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
            }
        }
        if (presented == null || !constantTimeEquals(presented, uiToken)) {
            return false;
        }
        // Secure quando servido atrás de TLS (proxy corporativo): TRACE2LOCAL_COOKIE_SECURE=true
        // ou X-Forwarded-Proto: https vindo do proxy
        boolean secure = "true".equalsIgnoreCase(setting("trace2local.cookie-secure", "TRACE2LOCAL_COOKIE_SECURE"))
                || "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + sessionValue
                + "; Path=" + cookiePath + "; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : ""));
        exchange.getResponseHeaders().set("Location", exchange.getRequestURI().getPath());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
        return true;
    }

    private boolean authenticated(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ") && constantTimeEquals(auth.substring(7), uiToken)) {
            return true;
        }
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return false;
        }
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(SESSION_COOKIE + "=")
                        && constantTimeEquals(p.substring(SESSION_COOKIE.length() + 1), sessionValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean hostAllowed(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false; // HTTP/1.1 exige Host; ausência é anômala
        }
        String host = hostOnly(hostHeader);
        if (allowedHosts.contains(host)) {
            return true;
        }
        // exposição explícita sem allowlist: o operador escolheu aceitar qualquer Host
        return explicitAnyHost;
    }

    private Set<String> allowedHosts(Trace2LocalConfig cfg) {
        Set<String> hosts = new LinkedHashSet<>(List.of("localhost", "127.0.0.1", "::1", "[::1]"));
        String configured = setting("trace2local.allowed-hosts", "TRACE2LOCAL_ALLOWED_HOSTS");
        boolean hasConfigured = false;
        if (configured != null) {
            for (String h : configured.split(",")) {
                String t = h.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    hosts.add(t);
                    hasConfigured = true;
                }
            }
        }
        if (!Trace2LocalHttpServer.isLoopback(cfg.bindAddress())) {
            if (!"0.0.0.0".equals(cfg.bindAddress()) && !"::".equals(cfg.bindAddress())) {
                hosts.add(cfg.bindAddress().toLowerCase(Locale.ROOT));
            }
            if (!hasConfigured) {
                explicitAnyHost = true;
                LOG.warning("bind fora do loopback sem trace2local.allowed-hosts: qualquer Host é aceito "
                        + "(defina TRACE2LOCAL_ALLOWED_HOSTS para fechar a UI contra DNS rebinding)");
            }
        }
        return hosts;
    }

    private String resolveUiToken(Trace2LocalConfig cfg) {
        String token = setting("trace2local.ui.token", "TRACE2LOCAL_UI_TOKEN");
        if (token != null && !token.isBlank() && !"off".equalsIgnoreCase(token)) {
            return token.trim();
        }
        boolean corporate = "corporate".equalsIgnoreCase(
                String.valueOf(setting("trace2local.security.profile", "TRACE2LOCAL_SECURITY_PROFILE")));
        if (corporate && !Trace2LocalHttpServer.isLoopback(cfg.bindAddress()) && !"off".equalsIgnoreCase(token)) {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            String generated = HexFormat.of().formatHex(random);
            LOG.warning("perfil corporate com bind fora do loopback: token de UI GERADO — abra "
                    + "http://<host>:" + cfg.port() + cfg.basePath() + "/?token=" + generated);
            return generated;
        }
        return null;
    }

    static String setting(String property, String env) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String hostOnly(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(0, end + 1) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon > 0 && h.indexOf(':') == colon ? h.substring(0, colon) : h;
    }

    private static boolean sameOrigin(String origin, String host) {
        try {
            URI o = URI.create(origin);
            String originHost = o.getHost() == null ? "" : o.getHost().toLowerCase(Locale.ROOT);
            int originPort = o.getPort() != -1 ? o.getPort() : ("https".equalsIgnoreCase(o.getScheme()) ? 443 : 80);
            String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
            String hostName = hostOnly(h).replace("[", "").replace("]", "");
            int hostPort = 80;
            int colon = h.lastIndexOf(':');
            if (colon > 0 && !h.endsWith("]")) {
                try {
                    hostPort = Integer.parseInt(h.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    hostPort = 80;
                }
            }
            return originHost.replace("[", "").replace("]", "").equals(hostName) && originPort == hostPort;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
