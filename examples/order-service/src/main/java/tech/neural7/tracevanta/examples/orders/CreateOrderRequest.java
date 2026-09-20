package tech.neural7.tracevanta.examples.orders;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank String customerId,
        @DecimalMin("0.01") BigDecimal total) {}
