package tech.neural7.trace2local.server;

import java.util.List;

/** Metadados do ambiente expostos em GET /api/meta (SPEC §5.1) — a UI se adapta ao que existe. */
public record Trace2LocalMeta(
        String app,
        String runtime,
        String mode,
        String version,
        List<String> capabilities) {

    public static final String VERSION = "0.1.0";

    public static Trace2LocalMeta embedded(String app) {
        return new Trace2LocalMeta(app, System.getProperty("java.version"), "embedded", VERSION,
                List.of("endpoints", "execute", "stream", "export"));
    }

    public static Trace2LocalMeta station() {
        return new Trace2LocalMeta("station", System.getProperty("java.version"), "companion", VERSION,
                List.of("endpoints", "stream", "export"));
    }
}
