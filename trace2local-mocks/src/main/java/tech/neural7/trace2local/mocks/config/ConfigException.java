package tech.neural7.trace2local.mocks.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuração inválida — carrega TODOS os erros, por chave. */
public final class ConfigException extends RuntimeException {

    private final Map<String, List<String>> errors;

    public ConfigException(String key, String message) {
        this(Map.of(key, List.of(message)));
    }

    public ConfigException(Map<String, List<String>> errors) {
        super(render(errors));
        this.errors = new LinkedHashMap<>(errors);
    }

    public Map<String, List<String>> errors() {
        return errors;
    }

    /** Só as mensagens (sem a chave) — usado quando a chave já é conhecida pelo chamador. */
    public List<String> messages() {
        List<String> out = new ArrayList<>();
        errors.values().forEach(out::addAll);
        return out;
    }

    private static String render(Map<String, List<String>> errors) {
        StringBuilder sb = new StringBuilder("configuração inválida");
        errors.forEach((k, v) -> sb.append("; ").append(k).append(": ").append(String.join(", ", v)));
        return sb.toString();
    }
}
