package tech.neural7.trace2local.mocks.spi;

import java.time.Duration;
import java.time.Instant;

/**
 * Uma chamada HTTP de saída vista num trace (nó {@code HTTP_CLIENT}).
 * Payloads chegam REDIGIDOS na origem — o replay nunca reproduz dado sensível.
 *
 * @param route        caminho com ids trocados por {@code {id}}
 * @param status       status HTTP, ou -1 quando não houve resposta (falha de conexão/timeout)
 * @param errorType    tipo da exceção quando a chamada falhou sem resposta
 * @param mocked       a resposta veio de um mock do Trace2Local (cabeçalho de marcação)
 */
public record ObservedExchange(String executionId, String nodeId, String flow, String peerService,
                               String host, int port, String method, String route, int status,
                               String requestBody, String responseBody, Duration duration,
                               Instant at, String errorType, String errorMessage, boolean mocked) {

    public boolean failedWithoutResponse() {
        return status < 0 && errorType != null;
    }

    public boolean successful() {
        return status >= 200 && status < 400;
    }

    /** Chave da operação: método + rota. */
    public String operation() {
        return method + " " + route;
    }

    public String hostPort() {
        return port > 0 ? host + ":" + port : host;
    }
}
