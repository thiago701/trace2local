package tech.neural7.tracevanta.spi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Descritor de endpoint descoberto (SPEC §4.8 / §5.1). {@code requestSchema} e
 * {@code sampleBody} são nulos quando não puderam ser inferidos — a UI mostra o
 * aviso, nunca um schema inventado.
 */
public record EndpointDescriptor(
        String endpointId,
        String method,
        String path,
        String handler,
        JsonNode requestSchema,
        String sampleBody) {

    public static EndpointDescriptor of(String endpointId, String method, String path, String handler) {
        return new EndpointDescriptor(endpointId, method, path, handler, null, null);
    }
}
