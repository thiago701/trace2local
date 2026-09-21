package tech.neural7.tracevanta.examples.payments;

import java.math.BigDecimal;

/** Pagamento Pix registrado no sistema. */
public record Payment(String key, String payer, BigDecimal amount, String status) {}
