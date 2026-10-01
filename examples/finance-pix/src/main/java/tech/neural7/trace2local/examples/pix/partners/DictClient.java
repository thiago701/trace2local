package tech.neural7.trace2local.examples.pix.partners;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.examples.pix.infra.Env;

import java.time.Duration;
import java.util.Optional;

/** DICT (BACEN): resolve a chave Pix na conta do recebedor. */
public final class DictClient extends PartnerHttp {

    public record Receiver(String ispb, String accountNumber, String accountType, String name) {}

    public DictClient() {
        super("DICT (BACEN)", Env.get("DICT_BASE_URL", "http://dict.bacen.local:8080/api/v2"), Duration.ofSeconds(3));
    }

    public Optional<Receiver> lookup(String pixKey) {
        Reply r = get("/entries/" + enc(pixKey));
        if (r.status() == 404) {
            return Optional.empty();
        }
        if (!r.ok()) {
            throw new PartnerUnavailableException("DICT (BACEN)", "HTTP " + r.status(), null);
        }
        JsonNode a = r.body().path("account");
        return Optional.of(new Receiver(a.path("participant").asText(), a.path("accountNumber").asText(),
                a.path("accountType").asText(), r.body().path("owner").path("name").asText()));
    }
}
