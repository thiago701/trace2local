package tech.neural7.trace2local.predictive.decision;

import java.util.List;
import java.util.Map;

/**
 * SPI de modelo de decisão (ADR-011): estado não estruturado entra, decisões
 * tipadas e probabilísticas saem — o contrato "System One" do Jev. Implementações:
 * {@link JevHttpModel} (API real), {@link JevCassetteModel} (replay
 * determinístico do que o Jev respondeu) e {@link DeterministicJevModel}
 * (mesmas primitivas, 100% local e reprodutível).
 */
public interface DecisionModel {

    /** Nome curto para telemetria/UI. */
    default String name() {
        return "custom";
    }

    /**
     * Responde as perguntas sobre o estado. Pode responder um SUBCONJUNTO
     * (perguntas sem resposta caem no próximo modelo da cascata).
     *
     * @throws DecisionException falha de transporte/autorização/orçamento — o
     *                           motor degrada para o modelo determinístico
     */
    Map<String, Answer> decide(Map<String, String> state, List<Question> questions) throws DecisionException;

    /** Falha recuperável do modelo (a cascata continua). */
    final class DecisionException extends Exception {
        private final String code;

        public DecisionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public DecisionException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /** {@code auth}, {@code network}, {@code budget}, {@code circuit-open}, {@code http-5xx}, {@code bad-response}… */
        public String code() {
            return code;
        }
    }
}
