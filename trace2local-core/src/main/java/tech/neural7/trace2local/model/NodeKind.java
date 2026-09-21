package tech.neural7.trace2local.model;

/**
 * Tipo de nó do TVEM (SPEC §4.6). O vocabulário é próprio do produto e estável —
 * nenhum nome de atributo do OpenTelemetry vaza para cá (ADR-008).
 */
public enum NodeKind {
    HTTP_SERVER,
    HTTP_CLIENT,
    BUSINESS,
    DYNAMODB,
    SQS,
    SNS,
    SQL,
    LAMBDA,
    UNKNOWN
}
