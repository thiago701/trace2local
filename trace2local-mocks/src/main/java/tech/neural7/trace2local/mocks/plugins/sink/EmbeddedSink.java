package tech.neural7.trace2local.mocks.plugins.sink;

import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.runtime.CompiledBinding;
import tech.neural7.trace2local.mocks.runtime.EmbeddedMockServer;
import tech.neural7.trace2local.mocks.runtime.MockServerAccess;
import tech.neural7.trace2local.mocks.spi.DeployRequest;
import tech.neural7.trace2local.mocks.spi.MockPluginException;
import tech.neural7.trace2local.mocks.spi.StubSink;

import java.util.List;

/**
 * {@code embedded}: o servidor de mocks do próprio Station — o único destino
 * DINÂMICO (template, n-ésima chamada, probabilidade, jitter) e o que conversa com
 * o roteamento do cliente. Padrão de todo binding.
 */
public final class EmbeddedSink implements StubSink {

    private final EmbeddedMockServer server;

    public EmbeddedSink(EmbeddedMockServer server) {
        this.server = server;
    }

    @Override
    public String name() {
        return "embedded";
    }

    @Override
    public String description() {
        return "Servidor de mocks do Station (porta própria): dinâmico, com journal, near-misses e roteamento do cliente.";
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public boolean dynamic() {
        return true;
    }

    @Override
    public Deployment deploy(DeployRequest request, MockConfig config) {
        if (!(request.runtime() instanceof CompiledBinding compiled)) {
            throw new MockPluginException("binding sem runtime compilado");
        }
        MockServerAccess.publish(server, compiled);
        return new Deployment(server.endpointOf(request.binding()),
                request.stubs().size() + " stub(s) no servidor embutido", List.of());
    }

    @Override
    public void undeploy(String binding, MockConfig config) {
        MockServerAccess.unpublish(server, binding);
    }
}
