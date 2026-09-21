package tech.neural7.trace2local.otel;

/** Rótulo legível de um nó: {@code "DynamoDB: orders"} (SPEC §4.5). */
public record NodeLabel(String text) {

    public static NodeLabel of(String text) {
        return new NodeLabel(text == null || text.isBlank() ? "?" : text);
    }
}
