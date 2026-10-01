package tech.neural7.trace2local.mocks.spi;

import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.model.MockRequest;
import tech.neural7.trace2local.mocks.model.MockResponse;

/**
 * Transformação de resposta encadeável — o "SMT" (Single Message Transform) do
 * Mock Connect. É assim que o dev valida <b>variações</b> do JSON que o
 * microsserviço consome sem editar o mock à mão: {@code set-field /decision DENIED},
 * {@code set-status 503}, {@code latency 3000}, {@code fault connection-reset}…
 */
public interface ResponseTransform extends MockPlugin {

    @Override
    default PluginType type() {
        return PluginType.TRANSFORM;
    }

    /** Instância configurada (validada). Chamada uma vez por binding/alias. */
    Transformation configure(MockConfig config);

    /**
     * {@code true} se o resultado depende só da resposta (pode ser "assado" em
     * destinos estáticos como WireMock/exportação); {@code false} se depende da
     * requisição ou do tempo (template, jitter).
     */
    default boolean isStatic() {
        return true;
    }

    @FunctionalInterface
    interface Transformation {
        MockResponse apply(MockRequest request, MockResponse response);
    }
}
