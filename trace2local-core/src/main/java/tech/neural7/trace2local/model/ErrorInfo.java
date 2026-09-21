package tech.neural7.trace2local.model;

/**
 * Informação de erro de um nó, com stack recortado (limitado na origem — SPEC §4.10).
 */
public record ErrorInfo(String type, String message, String stack) {}
