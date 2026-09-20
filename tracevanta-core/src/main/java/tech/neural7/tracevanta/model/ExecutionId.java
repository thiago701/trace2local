package tech.neural7.tracevanta.model;

/**
 * Identificador legível de execução exibido na UI, no formato {@code TV-NNNNN}
 * (SPEC §4.9).
 */
public final class ExecutionId {

    private ExecutionId() {}

    public static String parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("executionId não pode ser vazio");
        }
        String v = value.trim();
        if (!v.matches("TV-[0-9]{5,10}")) {
            throw new IllegalArgumentException("executionId fora do formato TV-NNNNN: " + v);
        }
        return v;
    }

    public static String format(long seq) {
        return "TV-" + String.format("%05d", Math.max(0, seq % 100_000));
    }
}
