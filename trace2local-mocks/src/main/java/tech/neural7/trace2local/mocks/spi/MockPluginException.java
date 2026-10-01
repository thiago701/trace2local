package tech.neural7.trace2local.mocks.spi;

/** Falha de um plugin em tempo de execução (arquivo ausente, destino fora do ar...). Mensagem para humanos. */
public class MockPluginException extends RuntimeException {

    public MockPluginException(String message) {
        super(message);
    }

    public MockPluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
