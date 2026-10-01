package tech.neural7.trace2local.predictive.decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uma micro-decisão no contrato das primitivas do Jev (TypeSafe "System One"):
 * <ul>
 *   <li>{@link Type#NOUL} — sim/não: probabilidade de a afirmação ser verdadeira;</li>
 *   <li>{@link Type#CHOICE} — escolha entre opções nomeadas com critério;</li>
 *   <li>{@link Type#SCORE} — nota numa escala ordenada de níveis (0..n-1).</li>
 * </ul>
 * O mesmo contrato é atendido pelo Jev real (HTTP) e pelo modelo determinístico
 * local — a UI e o {@code InsightService} não sabem quem respondeu, só leem a
 * procedência declarada em {@link Answer#engine()}.
 *
 * @param id           identificador estável (vira chave no JSON do Jev)
 * @param type         primitiva
 * @param instructions o que se pergunta (afirmação, no caso do NOUL)
 * @param criteria     opções → critério (CHOICE) — ordem preservada
 * @param levels       níveis em ordem crescente (SCORE)
 * @param family       família da pergunta (roteia skill determinística e política de fusão)
 * @param subject      sujeito local (nodeId, regra, índice de log) — nunca vai ao Jev
 */
public record Question(
        String id,
        Type type,
        String instructions,
        Map<String, String> criteria,
        List<String> levels,
        String family,
        String subject) {

    public enum Type { NOUL, CHOICE, SCORE }

    public static Question noul(String id, String family, String subject, String statement) {
        return new Question(id, Type.NOUL, statement, Map.of(), List.of(), family, subject);
    }

    public static Question choice(String id, String family, String subject, String instructions,
                                  LinkedHashMap<String, String> criteria) {
        return new Question(id, Type.CHOICE, instructions, criteria, List.of(), family, subject);
    }

    public static Question score(String id, String family, String subject, String instructions, List<String> levels) {
        return new Question(id, Type.SCORE, instructions, Map.of(), List.copyOf(levels), family, subject);
    }
}
