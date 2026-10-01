package tech.neural7.trace2local.mocks.model;

/**
 * Falhas de rede simuladas — as que o código do microsserviço precisa aguentar e
 * que uma API real raramente produz sob demanda (mesmo vocabulário do WireMock).
 */
public enum Fault {
    NONE,
    /** Fecha a conexão sem resposta (RST) — o cliente vê IOException. */
    CONNECTION_RESET,
    /** Cabeçalhos HTTP 200 e corpo vazio/truncado — o parser JSON do cliente quebra. */
    EMPTY_RESPONSE,
    /** Segura a conexão além do timeout do cliente e fecha sem responder. */
    TIMEOUT
}
