package tech.neural7.tracevanta.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.config.RedactionMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corpus de redaction (SPEC §8.3) — falso-negativo em campo óbvio é bug bloqueante.
 */
class RedactorTest {

    @Test
    void redactsSensitiveKeysCaseInsensitivePartialMatch() {
        ObjectNode payload = JsonSupport.MAPPER.createObjectNode();
        payload.put("password", "abc123");
        payload.put("userPassword", "abc123");
        payload.put("senha", "abc123");
        payload.put("apiKey", "abc123");
        payload.put("X-Api-Key", "abc123");
        payload.put("Authorization", "Basic abc");
        payload.put("accessKey", "abc");
        payload.put("session_token", "abc");
        payload.put("card", "abc");
        payload.put("cvv", "123");

        JsonNode redacted = Redactor.redactJson(payload, RedactionMode.STRICT);

        assertThat(redacted.get("password").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("userPassword").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("senha").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("apiKey").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("X-Api-Key").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("Authorization").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("accessKey").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("session_token").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("card").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("cvv").asText()).isEqualTo(Redactor.REDACTED);
    }

    @Test
    void redactsSensitiveValuePatternsInStrictMode() {
        assertThat(Redactor.redactString("joao.silva@example.com", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("AKIAIOSFODNN7EXAMPLE", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("-----BEGIN RSA PRIVATE KEY----- MIIE...", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("Bearer eyJraWQiOiIxIn0.abc.def", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("529.982.247-25", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("12.345.678/0001-95", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("4242424242424242", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        // tokens de provedores e hashes de senha (extensões do loop de segurança)
        assertThat(Redactor.redactString("ghp_0123456789abcdefghijklmnopqrstuvwxyzABCDEF", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
        assertThat(Redactor.redactString("$argon2id$v=19$m=65536,t=3,p=4$c29tZXNhbHQ$RdescudvJCsgt3ub+b+dWRWJTmaaJObG", RedactionMode.STRICT)).isEqualTo(Redactor.REDACTED);
    }

    @Test
    void redactsAdditionalSensitiveKeys() {
        ObjectNode payload = JsonSupport.MAPPER.createObjectNode();
        payload.put("jwt", "abc");
        payload.put("otp", "123456");
        payload.put("totp", "123456");
        payload.put("pwd", "abc");
        payload.put("privateKey", "-----BEGIN RSA PRIVATE KEY-----");

        JsonNode redacted = Redactor.redactJson(payload, RedactionMode.STRICT);

        assertThat(redacted.get("jwt").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("otp").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("totp").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("pwd").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("privateKey").asText()).isEqualTo(Redactor.REDACTED);
    }

    @Test
    void keysModeDoesNotRedactValuePatterns() {
        assertThat(Redactor.redactString("joao.silva@example.com", RedactionMode.KEYS)).isEqualTo("joao.silva@example.com");
    }

    @Test
    void offModeKeepsEverything() {
        assertThat(Redactor.redactString("joao.silva@example.com", RedactionMode.OFF)).isEqualTo("joao.silva@example.com");
        ObjectNode payload = JsonSupport.MAPPER.createObjectNode().put("password", "abc");
        assertThat(Redactor.redactJson(payload, RedactionMode.OFF).get("password").asText()).isEqualTo("abc");
    }

    @Test
    void innocentBusinessFieldPassesAndIsDocumentedLimit() {
        // limite declarado (SPEC §8.3): campo de negócio com nome inocente passa
        ObjectNode payload = JsonSupport.MAPPER.createObjectNode().put("observacao", "cliente pediu entrega rápida");
        assertThat(Redactor.redactJson(payload, RedactionMode.STRICT).get("observacao").asText())
                .isEqualTo("cliente pediu entrega rápida");
    }

    @Test
    void truncatesLongStringsWithMarker() {
        String longValue = "x".repeat(5000);
        String redacted = Redactor.redactString(longValue, RedactionMode.STRICT);
        assertThat(redacted).startsWith("xxx").endsWith(Redactor.TRUNCATED);
        assertThat(redacted.length()).isLessThan(1200);
    }

    @Test
    void capsPayloadWithValidJsonPrefixAndMarker() {
        ObjectNode payload = JsonSupport.MAPPER.createObjectNode();
        payload.put("field", "value");
        payload.put("big", "y".repeat(9000));
        JsonNode capped = Redactor.capPayload(payload, 200);
        assertThat(capped.has("_truncated")).isTrue();
        assertThat(capped.get("_truncated").asText()).isEqualTo(Redactor.TRUNCATED);
        assertThat(capped.get("payload").isObject()).isTrue();
    }

    @Test
    void doesNotTouchSmallPayloads() {
        ObjectNode payload = JsonSupport.MAPPER.createObjectNode().put("a", "b");
        assertThat(Redactor.capPayload(payload, 8192)).isSameAs(payload);
    }

    @Test
    void redactsNestedStructures() {
        ObjectNode inner = JsonSupport.MAPPER.createObjectNode().put("token", "secret-value");
        var array = JsonSupport.MAPPER.createArrayNode().add(inner).add("AKIAIOSFODNN7EXAMPLE");
        ObjectNode payload = JsonSupport.MAPPER.createObjectNode().set("nested", array);

        JsonNode redacted = Redactor.redactJson(payload, RedactionMode.STRICT);

        assertThat(redacted.get("nested").get(0).get("token").asText()).isEqualTo(Redactor.REDACTED);
        assertThat(redacted.get("nested").get(1).asText()).isEqualTo(Redactor.REDACTED);
        // entrada imutada
        assertThat(payload.get("nested").get(0).get("token")).isEqualTo(TextNode.valueOf("secret-value"));
    }
}
