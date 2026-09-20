package tech.neural7.tracevanta.server;

import java.util.List;

/** Metadados do ambiente expostos em GET /api/meta (SPEC §5.1) — a UI se adapta ao que existe. */
public record TraceVantaMeta(
        String app,
        String runtime,
        String mode,
        String version,
        List<String> capabilities) {

    public static final String VERSION = "0.1.0";

    public static TraceVantaMeta embedded(String app) {
        return new TraceVantaMeta(app, System.getProperty("java.version"), "embedded", VERSION,
                List.of("endpoints", "execute", "stream", "export"));
    }

    public static TraceVantaMeta station() {
        return new TraceVantaMeta("station", System.getProperty("java.version"), "companion", VERSION,
                List.of("endpoints", "stream", "export"));
    }
}
