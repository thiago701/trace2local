package tech.neural7.trace2local.model;

/**
 * Payload bruto (request/response) de um nó, já redigido e truncado na origem
 * (SPEC §4.10 — {@code trace2local.payload.max-bytes}, padrão 8 KB). Campos ausentes são {@code null}.
 */
public record Payload(String request, String response) {

    public static Payload requestOnly(String request) {
        return new Payload(request, null);
    }

    public static Payload responseOnly(String response) {
        return new Payload(null, response);
    }
}
