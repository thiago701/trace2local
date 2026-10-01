package tech.neural7.trace2local.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Configuração do servidor MCP — argumentos de linha de comando vencem variáveis de ambiente.
 *
 * <table>
 *   <caption>Variáveis</caption>
 *   <tr><td>{@code TRACE2LOCAL_URL}</td><td>base da UI/API (padrão {@code http://127.0.0.1:9876/trace2local};
 *       Station do compose: {@code http://127.0.0.1:19877/trace2local})</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_UI_TOKEN}</td><td>token de UI quando o perfil corporate o exige (vai como Bearer)</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MCP_ALLOW_MUTATIONS}</td><td>{@code true} expõe as ferramentas que disparam endpoints
 *       e alteram mocks (padrão: somente leitura)</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MCP_DATA}</td><td>{@code structural} (padrão: sem corpos de payload nem valores de
 *       dados) ou {@code full}</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MCP_ALLOW_REMOTE}</td><td>{@code true} permite base fora do loopback</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MCP_HTTP_PORT}</td><td>liga o transporte Streamable HTTP em 127.0.0.1:PORT</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MCP_HTTP_TOKEN}</td><td>Bearer exigido no transporte HTTP (opcional)</td></tr>
 *   <tr><td>{@code TRACE2LOCAL_MCP_TOOLS}</td><td>{@code all} (padrão), {@code core} (7 ferramentas essenciais —
 *       menos tokens residentes no harness) ou lista separada por vírgula</td></tr>
 * </table>
 */
public record McpConfig(
        URI baseUrl,
        String uiToken,
        boolean allowMutations,
        DataMode dataMode,
        boolean allowRemote,
        int httpPort,
        String httpToken,
        Duration timeout,
        int maxOutputChars,
        java.util.Set<String> toolFilter) {

    /** Perfil enxuto: o laço "por que falhou / o que simular" com ~1/3 do schema residente. */
    public static final java.util.Set<String> CORE_TOOLS = java.util.Set.of("status", "list_executions", "get_execution",
            "get_step", "diagnose_failure", "explain_execution", "list_mock_suggestions");

    public McpConfig(URI baseUrl, String uiToken, boolean allowMutations, DataMode dataMode, boolean allowRemote,
                     int httpPort, String httpToken, Duration timeout, int maxOutputChars) {
        this(baseUrl, uiToken, allowMutations, dataMode, allowRemote, httpPort, httpToken, timeout, maxOutputChars, null);
    }

    /** Quanto dado de negócio sai para o agente (e, portanto, para o modelo dele). */
    public enum DataMode {
        /** Estrutura, rótulos, tempos, erros e logs (já redigidos na origem); sem corpos nem valores de dados. */
        STRUCTURAL,
        /** Inclui corpos de payload e valores antes/depois (ainda redigidos na origem pelo Trace2Local). */
        FULL
    }

    public static final String DEFAULT_URL = "http://127.0.0.1:9876/trace2local";

    public McpConfig {
        if (baseUrl == null) {
            baseUrl = URI.create(DEFAULT_URL);
        }
        String s = baseUrl.toString();
        if (s.endsWith("/")) {
            baseUrl = URI.create(s.substring(0, s.length() - 1));
        }
        if (dataMode == null) {
            dataMode = DataMode.STRUCTURAL;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(15);
        }
        if (maxOutputChars <= 0) {
            maxOutputChars = 60_000;
        }
    }

    public static McpConfig from(String[] args, Map<String, String> env) {
        String url = env.getOrDefault("TRACE2LOCAL_URL", DEFAULT_URL);
        String token = env.get("TRACE2LOCAL_UI_TOKEN");
        boolean mutations = truthy(env.get("TRACE2LOCAL_MCP_ALLOW_MUTATIONS"));
        String data = env.getOrDefault("TRACE2LOCAL_MCP_DATA", "structural");
        boolean remote = truthy(env.get("TRACE2LOCAL_MCP_ALLOW_REMOTE"));
        int httpPort = parseInt(env.get("TRACE2LOCAL_MCP_HTTP_PORT"), 0);
        String httpToken = env.get("TRACE2LOCAL_MCP_HTTP_TOKEN");
        String toolset = env.getOrDefault("TRACE2LOCAL_MCP_TOOLS", "all");
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String next = i + 1 < args.length ? args[i + 1] : null;
            switch (a) {
                case "--url" -> { url = require(a, next); i++; }
                case "--token" -> { token = require(a, next); i++; }
                case "--allow-mutations" -> mutations = true;
                case "--data" -> { data = require(a, next); i++; }
                case "--allow-remote" -> remote = true;
                case "--http" -> { httpPort = parseInt(require(a, next), -1); i++; }
                case "--tools" -> { toolset = require(a, next); i++; }
                default -> throw new IllegalArgumentException("argumento desconhecido: " + a + " (use --help)");
            }
        }
        DataMode mode = switch (data.trim().toLowerCase(Locale.ROOT)) {
            case "full", "completo" -> DataMode.FULL;
            case "structural", "estrutural" -> DataMode.STRUCTURAL;
            default -> throw new IllegalArgumentException("TRACE2LOCAL_MCP_DATA/--data: use structural ou full");
        };
        if (httpPort < 0 || httpPort > 65535) {
            throw new IllegalArgumentException("--http: porta inválida");
        }
        URI base = URI.create(url.trim());
        if (base.getScheme() == null || !(base.getScheme().equals("http") || base.getScheme().equals("https"))
                || base.getHost() == null) {
            throw new IllegalArgumentException("TRACE2LOCAL_URL inválida: " + url);
        }
        if (!remote && !isLoopback(base.getHost())) {
            throw new IllegalArgumentException("TRACE2LOCAL_URL fora do loopback (" + base.getHost()
                    + "): traces e payloads não saem da máquina sem TRACE2LOCAL_MCP_ALLOW_REMOTE=true");
        }
        java.util.Set<String> filter = switch (toolset.trim().toLowerCase(Locale.ROOT)) {
            case "", "all", "todas" -> null;
            case "core", "essencial" -> CORE_TOOLS;
            default -> java.util.Set.of(toolset.trim().split("\\s*,\\s*"));
        };
        return new McpConfig(base, blankToNull(token), mutations, mode, remote, httpPort, blankToNull(httpToken),
                Duration.ofSeconds(15), 60_000, filter);
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]")
                || h.startsWith("127.");
    }

    private static String require(String flag, String value) {
        if (value == null || value.startsWith("--")) {
            throw new IllegalArgumentException(flag + " exige um valor");
        }
        return value;
    }

    private static boolean truthy(String v) {
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes"));
    }

    private static int parseInt(String v, int fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
