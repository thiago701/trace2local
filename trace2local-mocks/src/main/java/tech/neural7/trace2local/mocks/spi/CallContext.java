package tech.neural7.trace2local.mocks.spi;

import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Contexto de uma requisição atendida pelo mock.
 *
 * @param callNumber n-ésima chamada (1-based) que casou ESTE stub desde o (re)deploy do binding
 * @param pathParams variáveis do caminho do stub ({@code {key}} → valor)
 */
public record CallContext(String binding, String stubId, long callNumber, Map<String, String> pathParams,
                          RandomGenerator random) {}
