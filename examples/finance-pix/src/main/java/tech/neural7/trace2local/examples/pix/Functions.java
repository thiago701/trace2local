package tech.neural7.trace2local.examples.pix;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import tech.neural7.trace2local.examples.pix.api.PixApiHandler;
import tech.neural7.trace2local.examples.pix.notify.PixNotifierHandler;
import tech.neural7.trace2local.examples.pix.settlement.PixSettlementHandler;

import java.util.Map;

/** Registro das funções do pacote (um binário, três Lambdas — escolhidas por {@code _HANDLER}). */
public final class Functions {

    private Functions() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static RequestHandler<Map<String, Object>, Object> resolve(String name) {
        String n = name == null ? "" : name.trim();
        RequestHandler h = switch (n) {
            case "pix-api", "tech.neural7.trace2local.examples.pix.api.PixApiHandler" -> new PixApiHandler();
            case "pix-settlement", "tech.neural7.trace2local.examples.pix.settlement.PixSettlementHandler" -> new PixSettlementHandler();
            case "pix-notifier", "tech.neural7.trace2local.examples.pix.notify.PixNotifierHandler" -> new PixNotifierHandler();
            default -> throw new IllegalArgumentException("_HANDLER desconhecido: '" + n + "' (use pix-api, pix-settlement ou pix-notifier)");
        };
        return h;
    }
}
