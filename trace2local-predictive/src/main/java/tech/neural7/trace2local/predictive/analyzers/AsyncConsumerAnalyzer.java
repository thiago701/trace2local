package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.history.FlowHistory;

import java.util.ArrayList;
import java.util.List;

/**
 * ASYNC-ORPH-001 / PRED-CONS-001 — publicação sem consumidor observado (JC-3),
 * com PREDIÇÃO pelo histórico: se o mesmo fluxo SEMPRE teve consumidor e agora
 * não teve, a hipótese forte é "o consumidor parou" (mapeamento desligado,
 * função quebrada, fila errada) — não "ainda não chegou".
 */
public final class AsyncConsumerAnalyzer implements PredictiveAnalyzer {

    public static final String ORPHAN_ID = "ASYNC-ORPH-001";
    public static final String STOPPED_ID = "PRED-CONS-001";

    @Override
    public String name() {
        return "AsyncConsumerAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.EXECUTION;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        FlowView flow = ctx.flow();
        if (flow == null) {
            return List.of();
        }
        List<Insight> out = new ArrayList<>();
        for (FlowView.Step p : flow.steps()) {
            if (!p.producer()) {
                continue;
            }
            boolean consumed = flow.steps().stream().anyMatch(c -> p.node().nodeId().equals(c.parentId()) && c.asyncConsumer());
            if (consumed) {
                continue;
            }
            // histórico: quantas execuções anteriores do fluxo tiveram consumidor para este produtor?
            int previous = 0;
            int withConsumer = 0;
            if (ctx.history() != null) {
                for (FlowHistory.Sample s : ctx.history().samples(flow.flowKey())) {
                    if (s.executionId().equals(ctx.executionId()) || !s.components().contains(p.component())) {
                        continue;
                    }
                    previous++;
                    if (s.consumed().contains(p.component())) {
                        withConsumer++;
                    }
                }
            }
            boolean stopped = previous >= 3 && withConsumer >= 0.8 * previous;
            List<Evidence> ev = new ArrayList<>();
            ev.add(Evidence.span("publicação em " + Fmt.component(p.component()), "sem filho consumidor na árvore",
                    ctx.executionId(), p.node().nodeId()));
            if (previous > 0) {
                ev.add(Evidence.history("execuções anteriores com consumidor", withConsumer + " de " + previous));
            }
            out.add(InsightBuilder.of(stopped ? STOPPED_ID : ORPHAN_ID, name())
                    .subject(flow.flowKey() + "|" + p.component())
                    .category(stopped ? Insight.Category.PREDICTION : Insight.Category.ARCHITECTURE)
                    .severity(stopped ? Insight.Severity.HIGH : Insight.Severity.LOW)
                    .confidence(stopped ? 0.88 : 0.999)
                    .nature(stopped ? Insight.Nature.CORRELATION : Insight.Nature.FACT)
                    .title(stopped ? "Consumidor de " + Fmt.component(p.component()) + " parou de aparecer"
                            : "Mensagem publicada sem consumidor observado")
                    .observation("A publicação em " + Fmt.component(p.component()) + " não teve consumidor na mesma árvore"
                            + (stopped ? ", embora " + withConsumer + " de " + previous + " execuções anteriores tivessem." : "."))
                    .evidence(ev)
                    .correlation(stopped ? "Quebra de padrão histórico no mesmo fluxo." : null)
                    .hypothesis(stopped ? "Event source mapping desligado, consumidor falhando antes de instrumentar, ou fila trocada."
                            : "O consumidor pode estar fora do alcance da instrumentação (sem propagação de AWSTraceHeader) ou não existir.")
                    .recommend("Conferir o event source mapping / assinatura da fila e as métricas de idade da mensagem.")
                    .recommend("Garantir propagação do contexto (AWSTraceHeader/traceparent) até o consumidor.")
                    .component(p.component())
                    .execution(ctx.executionId())
                    .build());
        }
        return out;
    }
}
