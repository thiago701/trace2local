package tech.neural7.trace2local.examples.pix.partners;

import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.examples.pix.infra.Env;
import tech.neural7.trace2local.examples.pix.infra.Json;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Antifraude do parceiro: APPROVED segue, REVIEW vai para análise humana, DENIED recusa. */
public final class AntifraudeClient extends PartnerHttp {

    public record Score(String decision, int score, List<String> reasons) {}

    public AntifraudeClient() {
        super("Antifraude", Env.get("ANTIFRAUDE_BASE_URL", "http://antifraude.partner.local:8080"), Duration.ofSeconds(2));
    }

    public Score score(String transferId, String payerAccountId, String receiverIspb, BigDecimal amount) {
        ObjectNode body = Json.object().put("transferId", transferId).put("payerAccountId", payerAccountId)
                .put("receiverIspb", receiverIspb).put("amount", amount);
        Reply r = post("/v1/score", body);
        if (!r.ok()) {
            throw new PartnerUnavailableException("Antifraude", "HTTP " + r.status(), null);
        }
        List<String> reasons = new ArrayList<>();
        r.body().path("reasons").forEach(x -> reasons.add(x.asText()));
        // decisão desconhecida/ausente = REVIEW (falha segura: nunca aprova o que não entendeu)
        String decision = r.body().path("decision").asText("");
        if (!List.of("APPROVED", "REVIEW", "DENIED").contains(decision)) {
            decision = "REVIEW";
            reasons.add("DECISAO_DESCONHECIDA");
        }
        return new Score(decision, r.body().path("score").asInt(-1), reasons);
    }
}
