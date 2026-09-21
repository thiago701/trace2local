package tech.neural7.trace2local.examples.orders;

import java.math.BigDecimal;

public record Order(
        String orderId,
        String customerId,
        BigDecimal total,
        String status) {}
