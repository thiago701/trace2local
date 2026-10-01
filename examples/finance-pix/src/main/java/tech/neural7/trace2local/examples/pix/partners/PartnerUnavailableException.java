package tech.neural7.trace2local.examples.pix.partners;

/** Parceiro externo fora do ar / sem resposta / 5xx — vira 502 (pix-api) ou retry (assíncrono). */
public final class PartnerUnavailableException extends RuntimeException {

    private final String partner;

    public PartnerUnavailableException(String partner, String detail, Throwable cause) {
        super(partner + " indisponível: " + detail, cause);
        this.partner = partner;
    }

    public String partner() {
        return partner;
    }
}
