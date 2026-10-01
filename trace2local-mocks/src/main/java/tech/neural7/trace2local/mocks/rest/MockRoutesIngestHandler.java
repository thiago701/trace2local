package tech.neural7.trace2local.mocks.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;

import java.io.IOException;

/**
 * {@code GET /t2lingest/v1/mock-routes}: tabela de rotas para o roteamento do cliente
 * ({@code Trace2LocalMockRouting}). Fica no canal de ingest (mesmo token Bearer do
 * OTLP), não na API da UI: é consumida por serviços/Lambdas em containers, cujo
 * {@code Host} não passaria na allowlist anti-DNS-rebinding da UI.
 */
public final class MockRoutesIngestHandler implements HttpHandler {

    private final MockConnectWorker worker;

    public MockRoutesIngestHandler(MockConnectWorker worker) {
        this.worker = worker;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            MockConnectRestHandler.write(ex, 200, MockConnectRestHandler.JSON.valueToTree(worker.routes()));
        }
    }
}
