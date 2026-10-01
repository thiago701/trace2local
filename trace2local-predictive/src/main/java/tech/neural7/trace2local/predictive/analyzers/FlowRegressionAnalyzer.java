package tech.neural7.trace2local.predictive.analyzers;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * PERF-REG-001 — REGRESSÃO sustentada contra o baseline histórico do fluxo
 * ("a mediana era 420 ms; nas últimas execuções passou para 890 ms, +112%").
 * PRED-SHAPE-001 — MUDANÇA DE COMPORTAMENTO: o fluxo passou a tocar (ou deixou
 * de tocar) componentes que o histórico nunca viu.
 *
 * <p>Estatística robusta: mediana × mediana e z-score robusto (MAD), com
 * mínimo de amostras — sem histórico suficiente, silêncio (não há baseline).
 */
public final class FlowRegressionAnalyzer implements PredictiveAnalyzer {

    public static final String ID = "PERF-REG-001";
    public static final String SHAPE_ID = "PRED-SHAPE-001";

    @Override
    public String name() {
        return "FlowRegressionAnalyzer";
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
        List<Insight> out = new ArrayList<>();
        regression(ctx, flow).ifPresent(out::add);
        shapeChange(ctx, flow).ifPresent(out::add);
        return out;
    }

    private java.util.Optional<Insight> regression(AnalysisContext ctx, FlowView flow) {
        int minBaseline = (int) ctx.setting("regression.minBaseline", 5);
        FlowHistory.Comparison c = ctx.history().compare(flow.flowKey(), (int) ctx.setting("regression.window", 5));
        if (c == null || c.baselineCount() < minBaseline || c.recentCount() < 3) {
            return java.util.Optional.empty();
        }
        double minChange = ctx.setting("regression.minChange", 0.5);
        if (Double.isNaN(c.change()) || c.change() < minChange || c.robustZ() < 3 || c.recentP50() - c.baselineP50() < 50) {
            return java.util.Optional.empty();
        }
        List<Evidence> ev = new ArrayList<>();
        String baseLabel = c.mode().equals("dia-anterior") ? "baseline (dias anteriores)" : "baseline (execuções anteriores)";
        ev.add(Evidence.history(baseLabel + " p50 / p95", Fmt.ms(c.baselineP50()) + " / " + Fmt.ms(c.baselineP95())
                + " · " + c.baselineCount() + " amostras"));
        ev.add(Evidence.history("recente p50 / p95", Fmt.ms(c.recentP50()) + " / " + Fmt.ms(c.recentP95())
                + " · " + c.recentCount() + " amostras"));
        ev.add(Evidence.metric("variação da mediana", Fmt.change(c.change()) + " (z robusto " + Fmt.num(c.robustZ()) + ")"));
        ev.add(new Evidence(Evidence.Kind.SPAN, "execução mais recente", Fmt.ms(flow.endToEndMs()),
                Evidence.Ref.execution(ctx.executionId())));
        // atribuição: qual segmento cresceu?
        double dq = c.recentQueueP50() - c.baselineQueueP50();
        double dd = c.recentDbP50() - c.baselineDbP50();
        double dt = c.recentP50() - c.baselineP50();
        String correlation;
        if (dt > 0 && dq / dt >= 0.5) {
            correlation = "A maior parte do aumento (" + Fmt.pct(dq / dt) + ") está na espera em fila (" + Fmt.ms(c.baselineQueueP50())
                    + " → " + Fmt.ms(c.recentQueueP50()) + ").";
            ev.add(Evidence.history("espera em fila p50", Fmt.ms(c.baselineQueueP50()) + " → " + Fmt.ms(c.recentQueueP50())));
        } else if (dt > 0 && dd / dt >= 0.5) {
            correlation = "A maior parte do aumento (" + Fmt.pct(dd / dt) + ") está no acesso a banco (" + Fmt.ms(c.baselineDbP50())
                    + " → " + Fmt.ms(c.recentDbP50()) + ").";
            ev.add(Evidence.history("banco p50", Fmt.ms(c.baselineDbP50()) + " → " + Fmt.ms(c.recentDbP50())));
        } else {
            correlation = "O aumento não se concentra em fila nem em banco — está no código da aplicação ou em chamadas externas.";
        }
        Insight.Severity sev = c.change() >= 1.0 ? Insight.Severity.HIGH : Insight.Severity.MEDIUM;
        return java.util.Optional.of(InsightBuilder.of(ID, name())
                .subject(flow.flowKey())
                .category(Insight.Category.PREDICTION)
                .severity(sev)
                .confidence(Stats.evidenceConfidence(c.baselineCount() + c.recentCount(), c.robustZ() / 2, minBaseline))
                .nature(Insight.Nature.FACT)
                .title("Regressão de performance no fluxo " + Fmt.component(flow.flowKey()))
                .observation("A mediana do fluxo era " + Fmt.ms(c.baselineP50()) + "; nas últimas execuções passou para "
                        + Fmt.ms(c.recentP50()) + " (" + Fmt.change(c.change()) + ").")
                .evidence(ev)
                .correlation(correlation)
                .hypothesis("Uma mudança recente de código, dados ou configuração alterou o custo do fluxo.")
                .recommend("Comparar a execução mais lenta com uma do baseline (aba Comparar).")
                .recommend("Revisar commits/configurações alterados desde o baseline.")
                .recommend("Se a mudança for intencional, aceite o novo baseline marcando o insight como esperado.")
                .component(flow.steps().isEmpty() ? null : flow.steps().get(0).component())
                .execution(ctx.executionId())
                .build());
    }

    private java.util.Optional<Insight> shapeChange(AnalysisContext ctx, FlowView flow) {
        List<FlowHistory.Sample> samples = ctx.history().samples(flow.flowKey());
        String current = ctx.executionId();
        List<FlowHistory.Sample> previous = samples.stream().filter(s -> !s.executionId().equals(current)).toList();
        if (previous.size() < (int) ctx.setting("shape.minBaseline", 5)) {
            return java.util.Optional.empty();
        }
        Set<String> known = new HashSet<>();
        previous.forEach(s -> known.addAll(s.components()));
        Set<String> now = new TreeSet<>();
        flow.steps().forEach(s -> now.add(s.component()));
        Set<String> added = new TreeSet<>(now);
        added.removeAll(known);
        // componentes que estavam em TODAS as anteriores e sumiram
        Set<String> always = new TreeSet<>(previous.get(0).components());
        previous.forEach(s -> always.retainAll(s.components()));
        Set<String> removed = new TreeSet<>(always);
        removed.removeAll(now);
        // consumidor que sumiu atrás de um produtor sem consumo é do PRED-CONS-001 (um achado, não dois)
        boolean orphanProducer = flow.steps().stream().anyMatch(p -> p.producer() && flow.steps().stream()
                .noneMatch(c -> p.node().nodeId().equals(c.parentId()) && c.asyncConsumer()));
        if (orphanProducer) {
            removed.removeIf(r -> r.startsWith("lambda:") || r.startsWith("http:") || r.startsWith("dynamodb:")
                    || r.startsWith("sqs:") || r.startsWith("sns:"));
        }
        if (added.isEmpty() && removed.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<Evidence> ev = new ArrayList<>();
        added.forEach(a -> ev.add(new Evidence(Evidence.Kind.SPAN, "componente NOVO no fluxo", Fmt.component(a),
                new Evidence.Ref(current, nodeOf(flow, a), null, 0, a))));
        removed.forEach(r -> ev.add(Evidence.history("componente presente em todas as " + previous.size()
                + " execuções anteriores e AUSENTE agora", Fmt.component(r))));
        boolean consumerGone = removed.stream().anyMatch(r -> r.startsWith("lambda:") || r.startsWith("http:"));
        return java.util.Optional.of(InsightBuilder.of(SHAPE_ID, name())
                .subject(flow.flowKey() + "|" + String.join(",", added) + "|" + String.join(",", removed))
                .category(Insight.Category.PREDICTION)
                .severity(consumerGone ? Insight.Severity.HIGH : Insight.Severity.LOW)
                .confidence(0.999)
                .nature(Insight.Nature.FACT)
                .title("O fluxo " + Fmt.component(flow.flowKey()) + " mudou de forma")
                .observation((added.isEmpty() ? "" : "Passou a usar " + names(added) + ". ")
                        + (removed.isEmpty() ? "" : "Deixou de usar " + names(removed) + ".") )
                .evidence(ev)
                .correlation("Comparado a " + previous.size() + " execuções anteriores do mesmo fluxo.")
                .hypothesis(consumerGone ? "Um consumidor/serviço que sempre participava não foi observado — pode ter parado."
                        : "Mudança de código ou de dados levou o fluxo por outro caminho.")
                .recommend("Confirmar se a mudança de caminho é intencional.")
                .recommend("Abrir a execução na árvore e conferir o ramo alterado.")
                .component(flow.steps().isEmpty() ? null : flow.steps().get(0).component())
                .execution(current)
                .build());
    }

    private static String nodeOf(FlowView flow, String component) {
        return flow.steps().stream().filter(s -> s.component().equals(component)).map(s -> s.node().nodeId())
                .findFirst().orElse(null);
    }

    private static String names(Set<String> comps) {
        return String.join(", ", comps.stream().map(Fmt::component).toList());
    }
}
