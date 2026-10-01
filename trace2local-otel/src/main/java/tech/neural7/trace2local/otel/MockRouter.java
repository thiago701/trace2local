package tech.neural7.trace2local.otel;

import java.net.URI;
import java.util.Optional;

/**
 * Desvio de chamadas de saída para mocks (ADR-016). Implementação padrão:
 * {@link Trace2LocalMockRouting#fromEnv()} — tabela de rotas publicada pelo Station.
 * O nó continua com o host ORIGINAL ({@code server.address}) e ganha a marca de
 * simulado: a árvore nunca apresenta resposta de mock como real.
 */
@FunctionalInterface
public interface MockRouter {

    /** Destino alternativo para a chamada, ou vazio para seguir para a API real. */
    Optional<Route> route(URI original);

    /** @param binding nome do binding do Mock Connect que atende a chamada */
    record Route(URI target, String binding) {}

    static MockRouter none() {
        return uri -> Optional.empty();
    }
}
