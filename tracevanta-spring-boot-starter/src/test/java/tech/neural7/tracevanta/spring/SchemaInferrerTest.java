package tech.neural7.tracevanta.spring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaInferrerTest {

    record OrderRequest(String customerId, int quantity, boolean priority, List<String> tags) {}

    @Test
    void infersRecordSchemaAndSampleBody() {
        var schema = SchemaInferrer.schemaOf(OrderRequest.class);
        assertThat(schema).isNotNull();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").has("customerId")).isTrue();
        assertThat(schema.path("properties").get("customerId").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").get("quantity").path("type").asText()).isEqualTo("number");
        assertThat(schema.path("properties").get("priority").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("properties").get("tags").path("type").asText()).isEqualTo("array");
        assertThat(schema.path("required")).isNotNull();

        String sample = SchemaInferrer.sampleBodyOf(OrderRequest.class);
        assertThat(sample).contains("\"customerId\":\"string\"").contains("\"quantity\":0");
    }

    @Test
    void nonRecordClassYieldsNullHonestly() {
        // classe com campos privados: sem schema — nunca um schema inventado (SPEC §4.8)
        assertThat(SchemaInferrer.schemaOf(StringBuilder.class)).isNull();
        assertThat(SchemaInferrer.sampleBodyOf(StringBuilder.class)).isNull();
    }
}
