package tech.neural7.trace2local.predictive.decision;

import java.util.Map;

/**
 * POLÍTICA DE FUSÃO por família de micro-decisão — calibrada por EVIDÊNCIA, não
 * por gosto (benchmark rotulado {@code JevMicroDecisionBenchmarkTest}, 2026-09-30,
 * Jev 1.13.0, 94+ itens; ver {@code docs/qa/BENCHMARK-JEV.md}):
 *
 * <table>
 *   <caption>Medições que definiram cada política</caption>
 *   <tr><th>família</th><th>determinístico</th><th>Jev</th><th>decisão</th></tr>
 *   <tr><td>classificação de log</td><td>0,88</td><td>0,94 (26/26 acertos com conf ≥ 0,9)</td><td>JEV_FIRST τ=0,7</td></tr>
 *   <tr><td>papel arquitetural (BUSINESS)</td><td>0,75</td><td>0,83 (8/8 com conf ≥ 0,9)</td><td>JEV_FIRST τ=0,9</td></tr>
 *   <tr><td>desfecho da execução</td><td>0,80</td><td>0,50 com confiança 1,0 nos erros</td><td>RULE_FIRST</td></tr>
 *   <tr><td>dado pessoal (PII)</td><td>1,00*</td><td>0,93</td><td>RULE_FIRST</td></tr>
 *   <tr><td>divergência IaC intencional</td><td>0,75</td><td>0,58</td><td>RULE_FIRST</td></tr>
 * </table>
 * (*) léxico ajustado no mesmo conjunto — superestimado; revalidar com dados novos.
 *
 * <p>JEV_FIRST: vale o Jev com decisividade ≥ τ; abaixo, a regra. RULE_FIRST:
 * vale a regra; o Jev só decide quando a regra é fraca (&lt; 0,35) E ele passa de τ.
 * Famílias não medidas começam em RULE_FIRST (conservador) até haver benchmark.
 */
public final class FusionPolicy {

    public enum Trust { JEV_FIRST, RULE_FIRST }

    public record Policy(Trust trust, double tau) {}

    private static final Policy CONSERVATIVE = new Policy(Trust.RULE_FIRST, 0.9);

    private static final Map<String, Policy> BY_FAMILY = Map.of(
            QuestionCatalog.LOG_CLASS, new Policy(Trust.JEV_FIRST, 0.7),
            QuestionCatalog.NODE_ROLE, new Policy(Trust.JEV_FIRST, 0.9),
            QuestionCatalog.EXEC_OUTCOME, new Policy(Trust.RULE_FIRST, 0.95),
            QuestionCatalog.RULE_VERDICT, new Policy(Trust.RULE_FIRST, 0.9));

    private static final Map<String, Policy> BY_ID_PREFIX = Map.of(
            "pii_", new Policy(Trust.RULE_FIRST, 0.95),
            "iac_", new Policy(Trust.RULE_FIRST, 0.95),
            "idem_", new Policy(Trust.RULE_FIRST, 0.9),
            "perf_", new Policy(Trust.RULE_FIRST, 0.9));

    /** Peso do Jev na fusão NUMÉRICA (score): ainda não medido ⇒ metade da voz. */
    public static final double JEV_SCORE_WEIGHT = 0.5;

    private FusionPolicy() {}

    public static Policy of(Question q) {
        Policy p = BY_FAMILY.get(q.family());
        if (p != null) {
            return p;
        }
        for (var e : BY_ID_PREFIX.entrySet()) {
            if (q.id().startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return CONSERVATIVE;
    }

    /** Escolhe entre a resposta do modelo e a da regra; devolve qual valeu. */
    public static Answer choose(Question q, Answer model, Answer rule) {
        if (model == null) {
            return rule;
        }
        if (rule == null) {
            return model;
        }
        Policy p = of(q);
        if (p.trust() == Trust.JEV_FIRST) {
            return model.decisiveness() >= p.tau() ? model : rule;
        }
        return rule.decisiveness() < 0.35 && model.decisiveness() >= p.tau() ? model : rule;
    }
}
