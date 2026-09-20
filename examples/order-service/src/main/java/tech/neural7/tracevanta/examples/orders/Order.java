package tech.neural7.tracevanta.examples.orders;

import java.math.BigDecimal;

public record Order(
        String orderId,
        String customerId,
        BigDecimal total,
        String status) {}
