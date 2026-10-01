package tech.neural7.trace2local.predictive.decision;

import java.util.Map;

/**
 * Resposta a uma {@link Question}, no formato do Jev, com a PROCEDÊNCIA
 * declarada (invariante de honestidade do produto: a UI mostra quem decidiu e
 * com que confiança — nunca apresenta um palpite como fato).
 *
 * @param questionId    pergunta respondida
 * @param type          primitiva
 * @param choice        opção escolhida (CHOICE) ou nível textual (SCORE); {@code null} no NOUL
 * @param value         NOUL: P(verdadeiro); SCORE: valor esperado 0..n-1; CHOICE: prob. da escolhida
 * @param confidence    0..1 — sinal de ranking, NÃO probabilidade calibrada (ver ADR-011)
 * @param probabilities distribuição por opção/nível (vazia no NOUL)
 * @param engine        quem respondeu: {@code jev}, {@code jev-replay}, {@code jev-deterministic}, {@code fact}
 * @param model         versão do modelo (ex.: {@code jev-1.13.0}) ou do motor local
 * @param rationale     justificativa legível (motor determinístico/fatos; o Jev não explica)
 */
public record Answer(
        String questionId,
        Question.Type type,
        String choice,
        double value,
        double confidence,
        Map<String, Double> probabilities,
        String engine,
        String model,
        String rationale) {

    public static final String ENGINE_JEV = "jev";
    public static final String ENGINE_REPLAY = "jev-replay";
    public static final String ENGINE_DETERMINISTIC = "jev-deterministic";
    public static final String ENGINE_FACT = "fact";

    /** Confiança efetiva de um NOUL: distância da indecisão (0,5). */
    public double decisiveness() {
        if (type == Question.Type.NOUL) {
            return Math.abs(value - 0.5) * 2.0;
        }
        return confidence;
    }

    public boolean yes() {
        return type == Question.Type.NOUL && value >= 0.5;
    }

    public Answer withEngine(String newEngine) {
        return new Answer(questionId, type, choice, value, confidence, probabilities, newEngine, model, rationale);
    }

    public Answer withRationale(String newRationale) {
        return new Answer(questionId, type, choice, value, confidence, probabilities, engine, model, newRationale);
    }
}
