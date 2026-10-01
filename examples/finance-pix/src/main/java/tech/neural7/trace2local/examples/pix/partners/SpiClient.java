package tech.neural7.trace2local.examples.pix.partners;

import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.examples.pix.infra.Env;
import tech.neural7.trace2local.examples.pix.infra.Json;

import java.math.BigDecimal;
import java.time.Duration;

/** SPI (BACEN): liquidação da transferência. */
public final class SpiClient extends PartnerHttp {

    public SpiClient() {
        super("SPI (BACEN)", Env.get("SPI_BASE_URL", "http://spi.bacen.local:8080/spi/v1"), Duration.ofSeconds(5));
    }

    /** @return status devolvido pelo SPI (SETTLED/REJECTED) */
    public String settle(String endToEndId, BigDecimal amount, String payerIspb, String receiverIspb) {
        ObjectNode body = Json.object().put("endToEndId", endToEndId).put("amount", amount)
                .put("payerIspb", payerIspb).put("receiverIspb", receiverIspb);
        Reply r = post("/settlements", body);
        if (!r.ok()) {
            throw new PartnerUnavailableException("SPI (BACEN)", "HTTP " + r.status(), null);
        }
        return r.body().path("status").asText("REJECTED");
    }
}
