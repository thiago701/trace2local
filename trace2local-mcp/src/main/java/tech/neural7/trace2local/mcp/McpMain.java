package tech.neural7.trace2local.mcp;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ponto de entrada do servidor MCP do Trace2Local.
 *
 * <pre>
 *   java -jar trace2local-mcp-all.jar                         # stdio (padrão dos harnesses)
 *   java -jar trace2local-mcp-all.jar --url http://127.0.0.1:19877/trace2local
 *   java -jar trace2local-mcp-all.jar --http 7341             # Streamable HTTP em 127.0.0.1:7341/mcp
 *   java -jar trace2local-mcp-all.jar --allow-mutations       # dispara endpoints e altera mocks
 * </pre>
 */
public final class McpMain {

    private McpMain() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        for (String a : args) {
            if (a.equals("--help") || a.equals("-h")) {
                err.println(HELP);
                return;
            }
            if (a.equals("--version")) {
                err.println("trace2local-mcp " + McpServer.VERSION);
                return;
            }
        }
        quietLogging();
        McpConfig config;
        try {
            config = McpConfig.from(args, System.getenv());
        } catch (IllegalArgumentException e) {
            err.println("trace2local-mcp: " + e.getMessage());
            System.exit(2);
            return;
        }
        McpServer server = new McpServer(config);
        err.println("trace2local-mcp " + McpServer.VERSION + " → " + config.baseUrl()
                + (config.allowMutations() ? " · mutações LIGADAS" : " · somente leitura")
                + " · dados " + config.dataMode().name().toLowerCase(java.util.Locale.ROOT));
        if (config.httpPort() > 0) {
            HttpTransport http = new HttpTransport(server, config);
            int port = http.start(config.httpPort());
            err.println("Streamable HTTP em http://127.0.0.1:" + port + "/mcp" + (config.httpToken() != null ? " (Bearer exigido)" : ""));
            Runtime.getRuntime().addShutdownHook(new Thread(http::stop));
            Thread.currentThread().join();
            return;
        }
        new StdioTransport(server, System.in, System.out).run();
    }

    /** stdout é do protocolo: logging só em stderr e só aviso para cima. */
    private static void quietLogging() {
        Logger root = Logger.getLogger("");
        for (var h : root.getHandlers()) {
            root.removeHandler(h);
        }
        ConsoleHandler h = new ConsoleHandler(); // escreve em System.err
        h.setLevel(Level.WARNING);
        root.addHandler(h);
        root.setLevel(Level.WARNING);
    }

    private static final String HELP = """
            trace2local-mcp — servidor MCP do Trace2Local (agentes de código e harnesses)

            uso: java -jar trace2local-mcp-all.jar [opções]

              --url URL            base da UI/API (TRACE2LOCAL_URL; padrão http://127.0.0.1:9876/trace2local,
                                   Station do compose: http://127.0.0.1:19877/trace2local)
              --token T            token de UI do perfil corporate (TRACE2LOCAL_UI_TOKEN)
              --allow-mutations    expõe dispatch_endpoint e as ferramentas que alteram mocks
                                   (TRACE2LOCAL_MCP_ALLOW_MUTATIONS=true)
              --data MODO          structural (padrão: sem corpos de payload nem valores de dados) | full
                                   (TRACE2LOCAL_MCP_DATA)
              --allow-remote       aceita URL fora do loopback (TRACE2LOCAL_MCP_ALLOW_REMOTE=true)
              --tools PERFIL       all (padrão) | core (7 essenciais, menos tokens residentes) | lista a,b,c
                                   (TRACE2LOCAL_MCP_TOOLS)
              --http PORTA         Streamable HTTP em 127.0.0.1:PORTA/mcp em vez de stdio
                                   (TRACE2LOCAL_MCP_HTTP_PORT; Bearer opcional TRACE2LOCAL_MCP_HTTP_TOKEN)
              --version | --help

            Ferramentas: status, list_executions, get_execution, get_step, get_logs, diagnose_failure,
            explain_execution, list_insights, get_topology, compare_executions, list_endpoints,
            wait_for_execution, list_mock_suggestions, list_mock_bindings, get_mock_journal,
            list_mock_plugins, validate_mock_binding · com --allow-mutations: dispatch_endpoint,
            apply_mock_suggestion, put_mock_binding, control_mock_binding.
            Prompts: investigar-falha, validar-variacoes-de-parceiro, homologar-execucao.
            Guia: docs/MCP.md""";
}
