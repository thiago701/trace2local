package tech.neural7.tracevanta.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Mapper Jackson compartilhado do módulo AWS (sem jsr310 — só JsonNode aqui). */
final class JsonSupportHolder {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private JsonSupportHolder() {}
}
