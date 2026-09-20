package tech.neural7.tracevanta.spi;

import tech.neural7.tracevanta.model.NodeKind;

import java.util.Map;

/**
 * Contexto oferecido às extensões para capturar uma mutação de dados (SPEC §4.7).
 * O contrato do OpenTelemetry não permite enriquecer o span em {@code onEnd} —
 * o delta vive fora dele, neste canal (ADR-003).
 */
public record MutationContext(
        String spanId,
        String traceId,
        NodeKind kind,
        String label,
        Map<String, String> attributes) {}
