package tech.neural7.trace2local.server;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** Contrato do disparo de requisição pela UI (SPEC §4.9 / §5.1). */
public interface ExecutionLauncher {

    /** 202: a execução foi aceita; a árvore chega pelo SSE. */
    LaunchResult launch(ExecuteRequest request) throws Exception;

    record ExecuteRequest(
            String endpointId,
            Map<String, String> headers,
            JsonNode body,
            Map<String, String> pathVariables) {}

    record LaunchResult(String executionId, String traceId) {}
}
