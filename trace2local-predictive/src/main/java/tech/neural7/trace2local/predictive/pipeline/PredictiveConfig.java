package tech.neural7.trace2local.predictive.pipeline;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Configuração das Regras Assíncronas Preditivas (ADR-013). Propriedades
 * {@code trace2local.predictive.*} ou ambiente {@code TRACE2LOCAL_PREDICTIVE_*}.
 *
 * @param enabled          liga/desliga o pipeline inteiro
 * @param dataDir          pasta local de histórico/feedback/cassete ({@code .trace2local})
 * @param persistHistory   grava baseline/feedback em disco (privacy-first: só números)
 * @param queueCapacity    fila de execuções a analisar (cheia ⇒ descarte CONTADO)
 * @param parallelism      analisadores simultâneos por execução
 * @param analyzerTimeoutMs teto por analisador
 * @param corpusDebounceMs intervalo mínimo entre análises do acervo
 * @param projectRescanMs  intervalo mínimo entre re-varreduras do projeto
 * @param disabled         analisadores desligados (nome ou id)
 * @param settings         limiares dos analisadores ({@code async.minWaitMs=800} …)
 */
public record PredictiveConfig(
        boolean enabled,
        Path dataDir,
        boolean persistHistory,
        int queueCapacity,
        int parallelism,
        long analyzerTimeoutMs,
        long corpusDebounceMs,
        long projectRescanMs,
        Set<String> disabled,
        Map<String, Double> settings) {

    public static PredictiveConfig defaults() {
        return new PredictiveConfig(true, Path.of(".trace2local"), true, 256, 2, 2000, 3000, 60_000,
                Set.of(), Map.of());
    }

    public static PredictiveConfig fromEnvironment() {
        Map<String, String> raw = new LinkedHashMap<>();
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("TRACE2LOCAL_PREDICTIVE_")) {
                raw.put(k.substring("TRACE2LOCAL_PREDICTIVE_".length()).toLowerCase(Locale.ROOT).replace('_', '-'), v);
            }
        });
        Properties props = System.getProperties();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("trace2local.predictive.")) {
                raw.put(name.substring("trace2local.predictive.".length()), props.getProperty(name));
            }
        }
        String history = System.getProperty("trace2local.history", System.getenv("TRACE2LOCAL_HISTORY"));
        String dataDir = System.getProperty("trace2local.data-dir", System.getenv("TRACE2LOCAL_DATA_DIR"));
        Map<String, Double> settings = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k.startsWith("setting.")) {
                try {
                    settings.put(k.substring("setting.".length()), Double.parseDouble(v.trim()));
                } catch (NumberFormatException ignored) {
                    // limiar inválido: mantém o padrão
                }
            }
        });
        Set<String> disabled = new TreeSet<>();
        String off = raw.get("disabled");
        if (off != null) {
            for (String s : off.split(",")) {
                if (!s.isBlank()) {
                    disabled.add(s.trim());
                }
            }
        }
        return new PredictiveConfig(
                !"false".equalsIgnoreCase(raw.getOrDefault("enabled", "true").trim()),
                Path.of(dataDir == null || dataDir.isBlank() ? ".trace2local" : dataDir.trim()),
                !"off".equalsIgnoreCase(history == null ? "" : history.trim()),
                (int) num(raw.get("queue-capacity"), 256, 16, 10_000),
                (int) num(raw.get("parallelism"), 2, 1, 16),
                (long) num(raw.get("analyzer-timeout-ms"), 2000, 100, 60_000),
                (long) num(raw.get("corpus-debounce-ms"), 3000, 200, 600_000),
                (long) num(raw.get("project-rescan-ms"), 60_000, 1000, 3_600_000),
                disabled, settings);
    }

    private static double num(String raw, double fallback, double min, double max) {
        try {
            double v = raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Path historyFile() {
        return persistHistory ? dataDir.resolve("history").resolve("flows.json") : null;
    }

    public Path feedbackFile() {
        return persistHistory ? dataDir.resolve("history").resolve("feedback.json") : null;
    }
}
