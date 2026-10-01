package tech.neural7.trace2local.mocks.config;

import java.util.List;

/**
 * Resultado da validação de UMA chave — mesmo formato do {@code ConfigValue} do
 * Kafka Connect ({@code name, value, recommended_values, errors, visible}).
 */
public record ConfigValue(String name, Object value, List<String> recommendedValues, List<String> errors, boolean visible) {}
