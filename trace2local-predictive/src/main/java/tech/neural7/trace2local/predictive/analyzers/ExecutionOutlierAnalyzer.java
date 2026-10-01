package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.correlation.Stats;
import tech.neural7.trace2local.predictive.history.FlowHistory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PERF-OUT-001 — EXECUÇÃO FORA DA CURVA: esta execução está muito acima das
 * demais do mesmo fluxo (z robusto ≥ 3,5) — e o passo responsável é apontado
 * comparando cada componente com a duração típica dele no acervo.
 * Não dispara quando há regressão sustentada (PERF-REG-001 cobre).
 */
public final class ExecutionOutlierAnalyzer implements PredictiveAnalyzer {

    public static final String ID = "PERF-OUT-001";

    @Override
    public String name() {
        return "ExecutionOutlierAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.EXECUTION;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        FlowView flow = ctx.flow();
        if (flow == null || ctx.history() == null) {
            return List.of();
        }
        String current = ctx.executionId();
        List<Long> others = ctx.history().samples(flow.flowKey()).stream()
                .filter(s -> !s.executionId().equals(current)).map(FlowHistory.Sample::totalMs).toList();
        int min = (int) ctx.setting("outlier.minSamples", 8);
        if (others.size() < min) {
            return List.of();
        }
        double median = Stats.median(others);
        double z = Stats.robustZ(flow.endToEndMs(), others);
        if (z < ctx.setting("outlier.minZ", 3.5) || flow.endToEndMs() < median * 1.5 || flow.endToEndMs() - median < 100) {
            return List.of();
        }
        FlowHistory.Comparison cmp = ctx.history().compare(flow.flowKey(), 5);
        if (cmp != null && cmp.change() >= 0.5 && cmp.recentCount() >= 3) {
            return List.of(); // regressão sustentada — o PERF-REG-001 é o insight certo
        }
        // passo responsável: maior excesso contra a duração típica do componente no acervo
        Map<String, List<Long>> typical = new HashMap<>();
        for (Execution e : ctx.corpus()) {
            if (e.executionId().equals(current)) {
                continue;
            }
            FlowView other = FlowView.of(e);
            if (!other.flowKey().equals(flow.flowKey())) {
                continue;
            }
            other.steps().forEach(s -> typical.computeIfAbsent(s.component(), k -> new ArrayList<>()).add(s.durationMs()));
        }
        FlowView.Step culprit = null;
        double worstExcess = 0;
        double culpritTypical = 0;
        for (FlowView.Step s : flow.steps()) {
            List<Long> t = typical.get(s.component());
            double tm = t == null || t.isEmpty() ? 0 : Stats.median(t);
            double excess = s.durationMs() - tm;
            if (s.depth() > 0 && excess > worstExcess) {
                worstExcess = excess;
                culprit = s;
                culpritTypical = tm;
            }
        }
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence(Evidence.Kind.SPAN, "esta execução", Fmt.ms(flow.endToEndMs()), Evidence.Ref.execution(current)));
        ev.add(Evidence.history("mediana do fluxo", Fmt.ms(median) + " (" + others.size() + " execuções)"));
        ev.add(Evidence.metric("z-score robusto", Fmt.num(z)));
        String correlation = null;
        if (culprit != null) {
            ev.add(Evidence.span("passo com maior desvio: " + culprit.node().label(),
                    Fmt.ms(culprit.durationMs()) + " (típico " + Fmt.ms(culpritTypical) + ")", current, culprit.node().nodeId()));
            correlation = Fmt.pct(worstExcess / Math.max(1, flow.endToEndMs() - median)) + " do excesso vem de "
                    + culprit.node().label() + ".";
        }
        return List.of(InsightBuilder.of(ID, name())
                .subject(flow.flowKey())
                .category(Insight.Category.PERFORMANCE)
                .severity(z >= 6 ? Insight.Severity.MEDIUM : Insight.Severity.LOW)
                .confidence(Stats.evidenceConfidence(others.size(), z / 3, min))
                .nature(Insight.Nature.CORRELATION)
                .title("Execução muito acima das demais do fluxo")
                .observation("Esta execução levou " + Fmt.ms(flow.endToEndMs()) + "; a mediana do fluxo é " + Fmt.ms(median) + ".")
                .evidence(ev)
                .correlation(correlation)
                .hypothesis("Evento pontual (cold start, contenção, dado atípico) — se repetir, vira regressão.")
                .recommend("Abrir o passo destacado e comparar com uma execução típica.")
                .recommend("Verificar se o dado de entrada desta execução é atípico (volume, tamanho).")
                .component(culprit != null ? culprit.component() : null)
                .execution(current)
                .build());
    }
}
