package tech.neural7.trace2local.mocks.spi;

import tech.neural7.trace2local.mocks.model.Stub;

import java.util.List;

/**
 * O que o worker entrega ao destino.
 *
 * @param stubs     stubs prontos — para destinos estáticos, com as transformações já aplicadas
 * @param runtime   handle do binding compilado (destinos dinâmicos o usam por requisição); opaco para os demais
 */
public record DeployRequest(String binding, ApiTarget target, List<Stub> stubs, Object runtime) {}
