package tech.neural7.trace2local.mocks.spi;

import java.util.Locale;

/**
 * A dependência substituída pelo mock.
 *
 * @param name     nome lógico (ex.: "Antifraude") — rótulo na UI
 * @param hostPort {@code host[:porta]} da API real (o que o roteamento do cliente troca)
 */
public record ApiTarget(String name, String hostPort) {

    public String host() {
        int colon = hostPort.lastIndexOf(':');
        return (colon > 0 ? hostPort.substring(0, colon) : hostPort).toLowerCase(Locale.ROOT);
    }

    /** Porta explícita ou -1. */
    public int port() {
        int colon = hostPort.lastIndexOf(':');
        try {
            return colon > 0 ? Integer.parseInt(hostPort.substring(colon + 1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** O alvo casa com host/porta de uma chamada? (porta ausente no alvo = qualquer porta) */
    public boolean matches(String otherHost, int otherPort) {
        if (otherHost == null || !host().equalsIgnoreCase(otherHost)) {
            return false;
        }
        return port() < 0 || otherPort == port();
    }
}
