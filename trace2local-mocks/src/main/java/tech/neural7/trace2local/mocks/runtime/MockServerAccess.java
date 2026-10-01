package tech.neural7.trace2local.mocks.runtime;

/** Ponte de pacote: o sink embutido publica no servidor sem abrir a API de publicação ao mundo. */
public final class MockServerAccess {

    private MockServerAccess() {}

    public static void publish(EmbeddedMockServer server, CompiledBinding binding) {
        server.publish(binding);
    }

    public static void unpublish(EmbeddedMockServer server, String binding) {
        server.unpublish(binding);
    }
}
