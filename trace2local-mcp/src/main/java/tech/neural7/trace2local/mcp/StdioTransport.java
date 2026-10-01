package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Transporte stdio do MCP: uma mensagem JSON-RPC por linha em stdin/stdout (sem quebras
 * internas). stdout é EXCLUSIVO do protocolo — diagnóstico vai para stderr.
 */
final class StdioTransport {

    private final McpServer server;
    private final InputStream in;
    private final PrintStream out;

    StdioTransport(McpServer server, InputStream in, OutputStream out) {
        this.server = server;
        this.in = in;
        this.out = new PrintStream(out, false, StandardCharsets.UTF_8);
    }

    /**
     * Lê até EOF. Cada requisição roda numa virtual thread: uma ferramenta lenta
     * ({@code wait_for_execution}) não segura {@code ping} nem as demais chamadas.
     */
    void run() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = Json.parse(line);
                } catch (IOException e) {
                    write(McpServer.error(null, -32700, "JSON inválido"));
                    continue;
                }
                if (message != null && message.isObject() && "initialize".equals(message.path("method").asText())) {
                    write(server.handle(message)); // a negociação termina antes do resto
                    continue;
                }
                JsonNode m = message;
                pool.submit(() -> write(server.handle(m)));
            }
        }
    }

    private void write(JsonNode response) {
        if (response == null) {
            return;
        }
        String line = Json.write(response);
        synchronized (out) {
            out.print(line);
            out.print('\n');
            out.flush();
        }
    }
}
