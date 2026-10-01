package tech.neural7.trace2local.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Descritor de endpoint descoberto (SPEC §4.8 / §5.1). {@code requestSchema} e
 * {@code sampleBody} são nulos quando não puderam ser inferidos — a UI mostra o
 * aviso, nunca um schema inventado.
 *
 * @param parameters parâmetros declarados (contrato OpenAPI); vazio quando não há contrato
 */
public record EndpointDescriptor(
        String endpointId,
        String method,
        String path,
        String handler,
        JsonNode requestSchema,
        String sampleBody,
        List<Parameter> parameters) {

    /**
     * Parâmetro declarado: {@code in} = path | query | header. A UI pede os
     * obrigatórios antes do disparo; {@code example} pré-preenche o campo.
     */
    public record Parameter(String name, String in, boolean required, String example, String description) {}

    public EndpointDescriptor {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** Compatível com a v0.1 (sem parâmetros declarados). */
    public EndpointDescriptor(String endpointId, String method, String path, String handler, JsonNode requestSchema,
                              String sampleBody) {
        this(endpointId, method, path, handler, requestSchema, sampleBody, List.of());
    }

    public static EndpointDescriptor of(String endpointId, String method, String path, String handler) {
        return new EndpointDescriptor(endpointId, method, path, handler, null, null, List.of());
    }
}
