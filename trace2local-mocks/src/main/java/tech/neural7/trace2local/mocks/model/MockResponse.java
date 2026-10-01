package tech.neural7.trace2local.mocks.model;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.internal.JsonSupport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resposta de um stub (imutável — transformações devolvem cópias). */
public record MockResponse(int status, Map<String, String> headers, String body, long delayMs, Fault fault) {

    public MockResponse {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        fault = fault == null ? Fault.NONE : fault;
        delayMs = Math.max(0, delayMs);
    }

    public static MockResponse json(int status, JsonNode body) {
        return new MockResponse(status, Map.of("Content-Type", "application/json"),
                body == null ? null : JsonSupport.write(body), 0, Fault.NONE);
    }

    public static MockResponse json(int status, String body) {
        return new MockResponse(status, Map.of("Content-Type", "application/json"), body, 0, Fault.NONE);
    }

    public MockResponse withStatus(int newStatus) {
        return new MockResponse(newStatus, headers, body, delayMs, fault);
    }

    public MockResponse withBody(String newBody) {
        return new MockResponse(status, headers, newBody, delayMs, fault);
    }

    public MockResponse withJsonBody(JsonNode node) {
        return withBody(node == null ? null : JsonSupport.write(node));
    }

    public MockResponse withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new MockResponse(status, h, body, delayMs, fault);
    }

    public MockResponse withDelay(long ms) {
        return new MockResponse(status, headers, body, ms, fault);
    }

    public MockResponse withFault(Fault newFault) {
        return new MockResponse(status, headers, body, delayMs, newFault);
    }

    /** Corpo como JSON (ou {@code null} se ausente/não-JSON). */
    public JsonNode bodyJson() {
        return body == null || body.isBlank() ? null : JsonSupport.parse(body);
    }
}
