package tech.neural7.trace2local.predictive.decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Porta de MICRO-DECISÕES para analisadores (ADR-011/ADR-013): uma pergunta
 * tipada + uma resposta-padrão determinística (a regra do próprio analisador).
 * Se o Jev responder com confiança ≥ limiar, vale o Jev; senão vale a regra —
 * e a procedência fica registrada. Analisadores NUNCA chamam LLM direto.
 */
public final class DecisionPort {

    private final DecisionEngine engine;

    public DecisionPort(DecisionEngine engine) {
        this.engine = engine;
    }

    /** Porta sem modelo: sempre a regra determinística (testes, modo off). */
    public static DecisionPort rulesOnly() {
        return new DecisionPort(null);
    }

    /**
     * Decide um sim/não.
     *
     * @param state       estado MINIMIZADO e já redigido (vai ao Jev se o egresso estiver ligado)
     * @param statement   afirmação
     * @param fallbackP   probabilidade segundo a regra do analisador
     * @param rationale   justificativa da regra
     */
    public Answer noul(String id, Map<String, String> state, String statement, double fallbackP, String rationale) {
        Question q = Question.noul(id, "analyzer", id, statement);
        Answer rule = new Answer(id, Question.Type.NOUL, null, fallbackP, Math.abs(fallbackP - 0.5) * 2,
                Map.of(), Answer.ENGINE_DETERMINISTIC, DeterministicJevModel.VERSION, rationale);
        return decide(state, q, rule);
    }

    /** Decide uma escolha (opções em ordem; a regra indica a opção padrão). */
    public Answer choice(String id, Map<String, String> state, String instructions,
                         LinkedHashMap<String, String> criteria, String fallbackChoice, double fallbackConfidence,
                         String rationale) {
        Question q = Question.choice(id, "analyzer", id, instructions, criteria);
        Map<String, Double> probs = new LinkedHashMap<>();
        criteria.keySet().forEach(k -> probs.put(k, k.equals(fallbackChoice) ? fallbackConfidence
                : (1 - fallbackConfidence) / Math.max(1, criteria.size() - 1)));
        Answer rule = new Answer(id, Question.Type.CHOICE, fallbackChoice, fallbackConfidence, fallbackConfidence,
                probs, Answer.ENGINE_DETERMINISTIC, DeterministicJevModel.VERSION, rationale);
        return decide(state, q, rule);
    }

    private Answer decide(Map<String, String> state, Question q, Answer rule) {
        if (engine == null) {
            return rule;
        }
        Map<String, Answer> answers = engine.ask(state, List.of(q), (s, qs) -> Map.of(q.id(), rule));
        Answer a = answers.get(q.id());
        if (a == null) {
            return rule;
        }
        boolean model = Answer.ENGINE_JEV.equals(a.engine()) || Answer.ENGINE_REPLAY.equals(a.engine());
        if (!model) {
            return rule;
        }
        // política por família, calibrada no benchmark rotulado (FusionPolicy)
        Answer chosen = FusionPolicy.choose(q, a, rule);
        if (chosen == a) {
            return a.withRationale("Jev (" + a.model() + ") com confiança " + Math.round(a.decisiveness() * 100) + "%");
        }
        return rule.withRationale(rule.rationale() + " · Jev " + (sameVerdict(a, rule) ? "concorda" : "diverge")
                + " (" + Math.round(a.decisiveness() * 100) + "%) — regra mantida pela política " + FusionPolicy.of(q).trust());
    }

    private static boolean sameVerdict(Answer a, Answer b) {
        return a.type() == Question.Type.NOUL ? a.yes() == b.yes() : java.util.Objects.equals(a.choice(), b.choice());
    }

    public boolean modelAvailable() {
        return engine != null && engine.config().egressEnabled();
    }
}
