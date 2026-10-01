package tech.neural7.trace2local.mocks.spi;

/**
 * Papéis de plugin no pipeline {@code SOURCE → [TRANSFORM (+PREDICATE)]* → SINK}
 * — a mesma decomposição do Kafka Connect (source/sink connectors, SMTs, predicates).
 */
public enum PluginType {
    /** Produz stubs: contrato OpenAPI, tráfego observado nos traces, inline/arquivos WireMock. */
    SOURCE,
    /** Publica os stubs: servidor embutido do Station, WireMock (admin API), exportação em arquivo. */
    SINK,
    /** Altera a resposta (variações): campo, status, latência, falha de rede, template. */
    TRANSFORM,
    /** Decide quando uma transformação se aplica: caminho, método, cabeçalho, corpo, n-ésima chamada. */
    PREDICATE
}
