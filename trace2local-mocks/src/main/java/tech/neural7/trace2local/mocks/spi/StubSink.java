package tech.neural7.trace2local.mocks.spi;

import tech.neural7.trace2local.mocks.config.MockConfig;

import java.util.List;

/** Destino dos stubs (equivalente a um sink connector). */
public interface StubSink extends MockPlugin {

    @Override
    default PluginType type() {
        return PluginType.SINK;
    }

    /**
     * {@code true}: o destino executa transformações e predicados POR REQUISIÇÃO
     * (n-ésima chamada, probabilidade, template). {@code false}: o worker "assa" as
     * transformações estáticas nos stubs antes de publicar e recusa as dinâmicas.
     */
    boolean dynamic();

    /**
     * {@code true}: ao desligar o Station, o que foi publicado é removido (ex.: stubs no
     * WireMock do time voltam ao normal). {@code false}: o resultado é um artefato que
     * deve ficar (ex.: exportação em arquivo) ou morre junto com o processo (embutido).
     */
    default boolean undeployOnShutdown() {
        return false;
    }

    /** Publica (ou republica) os stubs do binding. */
    Deployment deploy(DeployRequest request, MockConfig config);

    /** Remove tudo que este binding publicou. Idempotente. */
    void undeploy(String binding, MockConfig config);

    /**
     * @param endpoint URL base onde a API simulada responde (o que o dev ou o roteamento usam)
     * @param detail   uma frase para o status ("12 stubs publicados no WireMock")
     */
    record Deployment(String endpoint, String detail, List<String> warnings) {}
}
