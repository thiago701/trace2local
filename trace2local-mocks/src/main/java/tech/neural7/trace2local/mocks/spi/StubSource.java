package tech.neural7.trace2local.mocks.spi;

import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.model.Stub;

import java.util.List;

/** Fonte de stubs (equivalente a um source connector). */
public interface StubSource extends MockPlugin {

    @Override
    default PluginType type() {
        return PluginType.SOURCE;
    }

    /**
     * Carrega os stubs do binding. Falha de leitura vira {@link MockPluginException}
     * com mensagem acionável (o binding fica {@code FAILED} com o motivo no status).
     */
    List<Stub> load(MockConfig config, SourceContext context);
}
