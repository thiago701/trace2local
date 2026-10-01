package tech.neural7.trace2local.predictive.ranking;

import tech.neural7.trace2local.predictive.api.Insight;

import java.time.Duration;
import java.time.Instant;

/**
 * Prioridade de um insight (ADR-013 §9):
 * <pre>
 *   bruto = impacto(categoria) × severidade × confiança × frequência × criticidade × novidade × precisão(analisador)
 *   score = 1 − e^(−3·bruto)            (0..1, saturação suave)
 * </pre>
 * <ul>
 *   <li><b>frequência</b> {@code 1 + ln(ocorrências)/3} — repetição reforça, sem explodir;</li>
 *   <li><b>criticidade</b> do fluxo: altera dados/dinheiro/assíncrono pesa mais;</li>
 *   <li><b>novidade</b>: 1,0 recém-visto; decai até 0,6 se o mesmo achado está aberto há dias;</li>
 *   <li><b>precisão do analisador</b>: aprendida do feedback local (útil × descartado)
 *       com prior Beta(3,1) — um analisador que gera ruído perde voz sozinho.</li>
 * </ul>
 */
public final class InsightRanker {

    private InsightRanker() {}

    public static double score(Insight i, double flowCriticality, double analyzerPrecision, Instant now) {
        double impact = switch (i.category()) {
            case SECURITY -> 1.0;
            case DATA, RESILIENCE -> 0.9;
            case PREDICTION, PERFORMANCE -> 0.8;
            case INFRASTRUCTURE, ARCHITECTURE -> 0.7;
            case TESTS -> 0.6;
        };
        double frequency = 1 + Math.log(Math.max(1, i.occurrences())) / 3.0;
        double ageDays = i.firstSeen() == null ? 0 : Duration.between(i.firstSeen(), now).toHours() / 24.0;
        double novelty = Math.max(0.6, 1.0 - 0.1 * ageDays);
        double raw = impact * i.severity().weight() * i.confidence() * frequency
                * clamp(flowCriticality, 0.5, 1.2) * novelty * clamp(analyzerPrecision, 0.2, 1.0);
        return Math.round((1 - Math.exp(-3 * raw)) * 1000.0) / 1000.0;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
