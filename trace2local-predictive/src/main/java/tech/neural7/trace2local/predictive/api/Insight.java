package tech.neural7.trace2local.predictive.api;

import java.time.Instant;
import java.util.List;

/**
 * Um INSIGHT — unidade de saída das Regras Assíncronas Preditivas (ADR-013).
 * Não é um alerta: é um ponto de investigação com a cadeia
 * {@code observação → evidência → correlação → hipótese → recomendação}, cada
 * elo com natureza declarada (fato × correlação × hipótese) e navegação até a
 * origem ({@link Evidence#ref()}).
 *
 * @param id                 código estável do tipo de achado (ex.: {@code PERF-ASYNC-001})
 * @param fingerprint        identidade para dedupe/cooldown/feedback ({@code id + sujeito})
 * @param category           categoria visual
 * @param severity           severidade (impacto potencial)
 * @param confidence         0..1 — força da evidência (1.0 só para fato observado)
 * @param nature             o que o insight AFIRMA: fato, correlação ou hipótese
 * @param title              uma linha, linguagem de investigação (não de alarme)
 * @param observation        o que foi observado (FATO)
 * @param evidence           evidências navegáveis (Evidence First)
 * @param correlation        relação entre as evidências (CORRELAÇÃO) — pode ser {@code null}
 * @param hypothesis         por que isso acontece, provavelmente (HIPÓTESE) — pode ser {@code null}
 * @param recommendations    o que investigar/fazer, em ordem
 * @param affectedComponents componentes envolvidos (rótulos da árvore/topologia)
 * @param executionIds       execuções que sustentam o insight
 * @param analyzer           analisador que produziu
 * @param decidedBy          quem tomou as micro-decisões ({@code rules}, {@code jev}, {@code jev-deterministic}…)
 * @param score              prioridade calculada pelo ranking (0..1) — preenchida pelo {@code InsightRanker}
 * @param occurrences        quantas vezes o mesmo fingerprint apareceu (dedupe)
 * @param firstSeen          primeira detecção
 * @param lastSeen           detecção mais recente
 */
public record Insight(
        String id,
        String fingerprint,
        Category category,
        Severity severity,
        double confidence,
        Nature nature,
        String title,
        String observation,
        List<Evidence> evidence,
        String correlation,
        String hypothesis,
        List<String> recommendations,
        List<String> affectedComponents,
        List<String> executionIds,
        String analyzer,
        String decidedBy,
        double score,
        int occurrences,
        Instant firstSeen,
        Instant lastSeen) {

    public enum Category {
        PERFORMANCE("⚡", "Performance"),
        ARCHITECTURE("⚠", "Arquitetura"),
        SECURITY("🔐", "Segurança"),
        TESTS("🧪", "Testes"),
        INFRASTRUCTURE("☁", "Infraestrutura"),
        RESILIENCE("🔁", "Resiliência"),
        DATA("🗄", "Dados"),
        PREDICTION("🧠", "Predição");

        private final String glyph;
        private final String label;

        Category(String glyph, String label) {
            this.glyph = glyph;
            this.label = label;
        }

        public String glyph() {
            return glyph;
        }

        public String label() {
            return label;
        }
    }

    public enum Severity {
        INFO(0.15), LOW(0.35), MEDIUM(0.6), HIGH(0.85), CRITICAL(1.0);

        private final double weight;

        Severity(double weight) {
            this.weight = weight;
        }

        public double weight() {
            return weight;
        }
    }

    /** Natureza da afirmação principal — a UI NUNCA apresenta hipótese como fato. */
    public enum Nature { FACT, CORRELATION, HYPOTHESIS }

    /** Faixa verbal da confiança (modelo de confiança, ADR-013 §7). */
    public String confidenceBand() {
        if (nature == Nature.FACT && confidence >= 0.999) {
            return "fato observado";
        }
        if (confidence >= 0.9) {
            return "forte evidência";
        }
        if (confidence >= 0.75) {
            return "provável";
        }
        if (confidence >= 0.55) {
            return "atenção";
        }
        return "hipótese fraca";
    }

    public Insight withRanking(double newScore, int newOccurrences, Instant newFirstSeen, Instant newLastSeen,
                               List<String> newExecutionIds) {
        return new Insight(id, fingerprint, category, severity, confidence, nature, title, observation, evidence,
                correlation, hypothesis, recommendations, affectedComponents, newExecutionIds, analyzer, decidedBy,
                newScore, newOccurrences, newFirstSeen, newLastSeen);
    }

    public Insight withDecidedBy(String engine, double newConfidence, Severity newSeverity) {
        return new Insight(id, fingerprint, category, newSeverity, newConfidence, nature, title, observation, evidence,
                correlation, hypothesis, recommendations, affectedComponents, executionIds, analyzer, engine,
                score, occurrences, firstSeen, lastSeen);
    }

    public Insight withHypothesis(String newHypothesis) {
        return new Insight(id, fingerprint, category, severity, confidence, nature, title, observation, evidence,
                correlation, newHypothesis, recommendations, affectedComponents, executionIds, analyzer, decidedBy,
                score, occurrences, firstSeen, lastSeen);
    }
}
