package tech.neural7.trace2local.mocks.config;

/**
 * Valor sensível (token de admin do WireMock, por exemplo). Nunca aparece em
 * {@code toString}, JSON de status, validação ou estado persistido — igual ao
 * tipo {@code PASSWORD} do Kafka. Prefira {@code ${env:NOME}} no config.
 */
public final class Password {

    public static final String HIDDEN = "[hidden]";

    private final String value;

    public Password(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return HIDDEN;
    }
}
