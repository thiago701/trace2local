package tech.neural7.trace2local.examples.pix.partners;

import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.examples.pix.infra.Env;
import tech.neural7.trace2local.examples.pix.infra.Json;

import java.time.Duration;

/** Notificações ao cliente (push/e-mail). */
public final class NotificationClient extends PartnerHttp {

    public NotificationClient() {
        super("Notificações", Env.get("NOTIFY_BASE_URL", "http://notify.partner.local:8080"), Duration.ofSeconds(2));
    }

    public String notify(String recipientId, String template, ObjectNode data) {
        ObjectNode body = Json.object().put("channel", "PUSH").put("recipientId", recipientId).put("template", template);
        body.set("data", data);
        Reply r = post("/v1/notifications", body);
        if (!r.ok()) {
            throw new PartnerUnavailableException("Notificações", "HTTP " + r.status(), null);
        }
        return r.body().path("notificationId").asText();
    }
}
