package tech.neural7.trace2local.predictive.assistant;

import tech.neural7.trace2local.predictive.decision.ExecutionFacts;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.LogFact;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.NodeFact;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.RuleFact;
import tech.neural7.trace2local.predictive.decision.IntelligenceConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ESTADO MINIMIZADO de uma execução para o modelo de decisão (ADR-011):
 * <ul>
 *   <li>{@code structural}: rótulos, tipos, operações, status, durações, tipo e
 *       mensagem de erro, tipo de mutação, alvo e NOMES de campos alterados;</li>
 *   <li>{@code values}: + valores antes → depois dos campos alterados.</li>
 * </ul>
 * Payloads brutos e atributos livres NUNCA entram. A redação final acontece no
 * {@code EgressSanitizer} antes de sair da máquina.
 */
public final class StateBuilder {

    private StateBuilder() {}

    public static Map<String, String> of(ExecutionFacts f, IntelligenceConfig.Egress egress) {
        boolean values = egress == IntelligenceConfig.Egress.VALUES;
        Map<String, String> s = new LinkedHashMap<>();
        s.put("execution", "trigger " + f.trigger() + " | status " + f.status() + " | " + f.durationMs() + " ms | "
                + f.nodes().size() + " steps | warnings " + f.warnings());
        StringBuilder steps = new StringBuilder();
        for (NodeFact n : f.nodes()) {
            steps.append("step ").append(n.order()).append(": ").append("  ".repeat(Math.min(6, n.depth())))
                    .append('[').append(n.kind()).append("] ").append(n.label());
            if (n.operation() != null && !n.operation().isBlank()) {
                steps.append(" (").append(n.operation()).append(')');
            }
            steps.append(" — ").append(n.status()).append(' ').append(n.totalMs()).append(" ms");
            if (n.producer()) {
                steps.append(" — publishes message");
            }
            if (n.asyncConsumer()) {
                steps.append(" — async consumer");
            }
            if (n.mutationKind() != null) {
                steps.append(" — data ").append(n.mutationKind()).append(" on ").append(n.mutationTarget());
                if (!n.changedFields().isEmpty()) {
                    steps.append(" fields ");
                    if (values) {
                        int i = 0;
                        for (var e : n.deltaValues().entrySet()) {
                            if (i++ > 0) {
                                steps.append(", ");
                            }
                            steps.append(e.getKey()).append(": ").append(e.getValue()[0]).append(" → ").append(e.getValue()[1]);
                        }
                    } else {
                        steps.append(String.join(", ", n.changedFields()));
                    }
                }
            }
            if (n.failed()) {
                steps.append(" — ERROR ").append(n.errorType());
                if (n.errorMessage() != null) {
                    String m = n.errorMessage();
                    steps.append(": ").append(m.length() > 200 ? m.substring(0, 200) + "…" : m);
                }
            }
            steps.append('\n');
            if (steps.length() > 5000) {
                steps.append("…\n");
                break;
            }
        }
        s.put("steps", steps.toString());
        if (!f.rules().isEmpty()) {
            StringBuilder rules = new StringBuilder();
            for (RuleFact r : f.rules()) {
                rules.append(r.id()).append(' ').append(r.term()).append(": ").append(r.text()).append('\n');
            }
            s.put("business_glossary", rules.toString());
        }
        long errors = f.logs().stream().filter(l -> "ERROR".equalsIgnoreCase(l.level()) || "WARN".equalsIgnoreCase(l.level())).count();
        if (!f.logs().isEmpty()) {
            StringBuilder logs = new StringBuilder();
            logs.append(f.logs().size()).append(" log lines, ").append(errors).append(" warn/error\n");
            int shown = 0;
            for (LogFact l : f.logs()) {
                if (l.platform() || shown >= 8) {
                    continue;
                }
                if ("ERROR".equalsIgnoreCase(l.level()) || "WARN".equalsIgnoreCase(l.level())) {
                    String m = l.message() == null ? "" : l.message();
                    logs.append(l.level()).append(": ").append(m.length() > 160 ? m.substring(0, 160) + "…" : m).append('\n');
                    shown++;
                }
            }
            s.put("logs", logs.toString());
        }
        return s;
    }
}
