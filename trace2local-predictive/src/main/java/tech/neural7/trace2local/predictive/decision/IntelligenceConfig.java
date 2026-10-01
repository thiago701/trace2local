package tech.neural7.trace2local.predictive.decision;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;

/**
 * Configuração da inteligência (ADR-011). Resolvida de propriedades de sistema
 * ({@code trace2local.jev.*}) ou ambiente ({@code TRACE2LOCAL_JEV_*}) — vale para
 * Embedded e Station sem tocar no {@code Trace2LocalConfig} público.
 *
 * <h2>Modos</h2>
 * <ul>
 *   <li>{@code auto} (padrão): com chave → {@code live}; sem chave → {@code deterministic};</li>
 *   <li>{@code live}: chama o Jev; falha/orçamento/circuito aberto ⇒ cai no determinístico;</li>
 *   <li>{@code record}: como {@code live}, gravando as respostas num cassete (só hashes e respostas — nenhum dado);</li>
 *   <li>{@code replay}: 100% offline, responde do cassete; o que faltar ⇒ determinístico;</li>
 *   <li>{@code deterministic}: só o modelo determinístico local (mesmas primitivas do Jev);</li>
 *   <li>{@code off}: sem perguntas — a UI mostra apenas fatos.</li>
 * </ul>
 *
 * <h2>Egresso (o que sai da máquina no live/record)</h2>
 * {@code structural} (padrão): rótulos, tipos, status, durações, tipos/mensagens de
 * erro e NOMES de campos alterados — tudo redigido. {@code values}: inclui os
 * valores antes/depois dos campos alterados (redigidos), necessário para regras
 * que dependem de valor (ex.: {@code PENDING → CONFIRMED}).
 */
public record IntelligenceConfig(
        Mode mode,
        String apiKey,
        String endpoint,
        String model,
        Egress egress,
        double acceptThreshold,
        int timeoutMs,
        int maxQuestionsPerRequest,
        int maxRequestsPerMinute,
        long maxInputTokensPerDay,
        Path cassette) {

    public enum Mode { AUTO, LIVE, RECORD, REPLAY, DETERMINISTIC, OFF }

    public enum Egress { STRUCTURAL, VALUES }

    public static final String DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone";

    /** Modo efetivo depois de resolver o {@code auto}. */
    public Mode effectiveMode() {
        if (mode != Mode.AUTO) {
            return mode;
        }
        return hasKey() ? Mode.LIVE : Mode.DETERMINISTIC;
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Egresso para a API externa acontece neste modo? */
    public boolean egressEnabled() {
        Mode m = effectiveMode();
        return hasKey() && (m == Mode.LIVE || m == Mode.RECORD);
    }

    public static IntelligenceConfig fromEnvironment() {
        return from(IntelligenceConfig::lookup);
    }

    /** Resolução testável (fonte de configuração injetada). */
    public static IntelligenceConfig from(Function<String, String> source) {
        Mode mode = parseEnum(Mode.class, source.apply("mode"), Mode.AUTO);
        String enabled = source.apply("enabled");
        if (enabled != null && "false".equalsIgnoreCase(enabled.trim())) {
            mode = Mode.DETERMINISTIC; // kill-switch de egresso: nunca chama a API
        }
        String key = source.apply("api-key");
        String endpoint = orDefault(source.apply("endpoint"), DEFAULT_ENDPOINT);
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://127.0.0.1")
                && !endpoint.startsWith("http://localhost")) {
            // chave Bearer só viaja cifrada (exceção: gateway/mock em loopback)
            endpoint = DEFAULT_ENDPOINT;
        }
        return new IntelligenceConfig(
                mode,
                key == null ? null : key.trim(),
                endpoint,
                orDefault(source.apply("model"), "jev-latest"),
                parseEnum(Egress.class, source.apply("egress"), Egress.STRUCTURAL),
                clamp(parseDouble(source.apply("accept-threshold"), 0.80), 0.5, 0.999),
                (int) clamp(parseDouble(source.apply("timeout-ms"), 4000), 500, 30_000),
                (int) clamp(parseDouble(source.apply("max-questions"), 48), 1, 200),
                (int) clamp(parseDouble(source.apply("max-requests-per-minute"), 60), 1, 6000),
                (long) clamp(parseDouble(source.apply("max-input-tokens-per-day"), 5_000_000), 1_000, 1e12),
                Path.of(orDefault(source.apply("cassette"), ".trace2local/jev-cassette.jsonl")));
    }

    /** {@code trace2local.jev.<k>} (sistema) → {@code TRACE2LOCAL_JEV_<K>} (ambiente); a chave também aceita {@code JEV_API_KEY}. */
    static String lookup(String key) {
        String v = System.getProperty("trace2local.jev." + key);
        if (v == null || v.isBlank()) {
            v = System.getenv("TRACE2LOCAL_JEV_" + key.toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        if ((v == null || v.isBlank()) && "api-key".equals(key)) {
            v = System.getenv("JEV_API_KEY");
        }
        return v == null || v.isBlank() ? null : v;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** Nunca imprime a chave (toString do record a exporia). */
    @Override
    public String toString() {
        return "IntelligenceConfig[mode=" + mode + ", effective=" + effectiveMode() + ", key=" + (hasKey() ? "configurada" : "ausente")
                + ", endpoint=" + endpoint + ", model=" + model + ", egress=" + egress + "]";
    }
}
