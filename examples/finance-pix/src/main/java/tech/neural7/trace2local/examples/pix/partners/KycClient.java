package tech.neural7.trace2local.examples.pix.partners;

import tech.neural7.trace2local.examples.pix.infra.Env;

import java.math.BigDecimal;
import java.time.Duration;

/** Bureau KYC & limites: situação cadastral e quanto o pagador ainda pode transferir hoje. */
public final class KycClient extends PartnerHttp {

    public record Limits(String kycStatus, BigDecimal availableToday) {}

    public KycClient() {
        super("KYC & Limites", Env.get("KYC_BASE_URL", "http://kyc.bureau.local:8080"), Duration.ofSeconds(3));
    }

    public Limits limits(String customerId) {
        Reply r = get("/v2/customers/" + enc(customerId) + "/limits");
        if (!r.ok()) {
            throw new PartnerUnavailableException("KYC & Limites", "HTTP " + r.status(), null);
        }
        return new Limits(r.body().path("kycStatus").asText("UNKNOWN"),
                r.body().path("availableToday").decimalValue());
    }
}
