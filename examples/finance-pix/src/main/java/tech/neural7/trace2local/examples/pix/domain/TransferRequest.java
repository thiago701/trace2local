package tech.neural7.trace2local.examples.pix.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** Pedido de transferência (validado contra o contrato pix-api.yaml). */
public record TransferRequest(String payerAccountId, String pixKey, BigDecimal amount, String description) {

    public static TransferRequest from(JsonNode body) {
        String payer = text(body, "payerAccountId");
        String key = text(body, "pixKey");
        JsonNode amountNode = body.get("amount");
        if (payer == null || key == null || amountNode == null || !amountNode.isNumber()) {
            throw new Problem(400, "INVALID_REQUEST", "payerAccountId, pixKey e amount (número) são obrigatórios");
        }
        BigDecimal amount = amountNode.decimalValue();
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000")) > 0 || amount.scale() > 2) {
            throw new Problem(400, "INVALID_REQUEST", "amount deve estar entre 0,01 e 1.000.000,00 com até 2 casas");
        }
        String description = text(body, "description");
        if (description != null && description.length() > 140) {
            throw new Problem(400, "INVALID_REQUEST", "description excede 140 caracteres");
        }
        return new TransferRequest(payer, key, amount, description);
    }

    /** Forma canônica para o hash de idempotência (mesma chave + payload diferente = conflito). */
    public String canonical() {
        return payerAccountId + "|" + pixKey + "|" + amount.stripTrailingZeros().toPlainString() + "|" + (description == null ? "" : description);
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText().trim();
    }
}
