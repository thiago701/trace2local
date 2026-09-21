package tech.neural7.tracevanta.examples.payments;

import java.math.BigDecimal;

/** Requisição de criação de Pix (idempotente pela chave). */
public record CreatePaymentRequest(String key, String payer, BigDecimal amount) {}
