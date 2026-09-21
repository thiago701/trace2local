package tech.neural7.trace2local.server;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.time.Duration;

/**
 * Codec JSON dos contratos REST/SSE: {@code Instant} em ISO-8601 e
 * {@code Duration} em milissegundos (número) — forma simples e estável para a UI.
 */
public final class JsonCodec {

    public static final ObjectMapper MAPPER = build();

    private JsonCodec() {}

    private static ObjectMapper build() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Duration.class, new JsonSerializer<>() {
            @Override
            public void serialize(Duration value, com.fasterxml.jackson.core.JsonGenerator gen,
                                  com.fasterxml.jackson.databind.SerializerProvider serializers) throws IOException {
                if (value == null) {
                    gen.writeNull();
                } else {
                    gen.writeNumber(value.toMillis());
                }
            }
        });
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(module)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
