package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PRED-ERR-001 — INSTABILIDADE EMERGENTE: a taxa de falha de uma entrada nas
 * execuções recentes subiu de forma estatisticamente significativa em relação às
 * anteriores (teste de duas proporções, z ≥ 1,96, e salto ≥ 20 p.p.).
 * Agrupa por ENTRADA (não por fluxo), porque o caminho de erro tem outra forma.
 */
public final class ErrorTrendAnalyzer implements PredictiveAnalyzer {

    public static final String ID = "PRED-ERR-001";

    @Override
    public String name() {
        return "ErrorTrendAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.CORPUS;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        if (ctx.corpus() == null || ctx.corpus().size() < 12) {
            return List.of();
        }
        Map<String, List<Execution>> byEntry = new LinkedHashMap<>();
        for (Execution e : ctx.corpus()) {
            FlowView f = FlowView.of(e);
            if (f.steps().isEmpty()) {
                continue;
            }
            byEntry.computeIfAbsent(f.steps().get(0).component(), k -> new ArrayList<>()).add(e);
        }
        int window = (int) ctx.setting("errors.window", 8);
        List<Insight> out = new ArrayList<>();
        for (var entry : byEntry.entrySet()) {
            List<Execution> list = new ArrayList<>(entry.getValue());
            list.sort(Comparator.comparing(Execution::startedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            if (list.size() < window + 6) {
                continue;
            }
            List<Execution> recent = list.subList(list.size() - window, list.size());
            List<Execution> before = list.subList(0, list.size() - window);
            long fr = recent.stream().filter(ErrorTrendAnalyzer::failed).count();
            long fb = before.stream().filter(ErrorTrendAnalyzer::failed).count();
            double pr = (double) fr / recent.size();
            double pb = (double) fb / before.size();
            double pooled = (double) (fr + fb) / (recent.size() + before.size());
            double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / recent.size() + 1.0 / before.size()));
            double z = se == 0 ? 0 : (pr - pb) / se;
            if (z < 1.96 || pr - pb < 0.2) {
                continue;
            }
            Execution lastFailed = recent.stream().filter(ErrorTrendAnalyzer::failed).reduce((a, b) -> b).orElse(null);
            List<Evidence> ev = new ArrayList<>();
            ev.add(Evidence.history("falhas nas últimas " + recent.size(), fr + " (" + Fmt.pct(pr) + ")"));
            ev.add(Evidence.history("falhas nas " + before.size() + " anteriores", fb + " (" + Fmt.pct(pb) + ")"));
            ev.add(Evidence.metric("teste de duas proporções", "z = " + Fmt.num(z)));
            if (lastFailed != null) {
                ev.add(new Evidence(Evidence.Kind.SPAN, "falha mais recente", lastFailed.executionId(),
                        Evidence.Ref.execution(lastFailed.executionId())));
            }
            out.add(InsightBuilder.of(ID, name())
                    .subject(entry.getKey())
                    .category(Insight.Category.PREDICTION)
                    .severity(pr >= 0.5 ? Insight.Severity.HIGH : Insight.Severity.MEDIUM)
                    .confidence(Math.min(0.97, 0.6 + (z - 1.96) * 0.1 + 0.15))
                    .nature(Insight.Nature.CORRELATION)
                    .title("Falhas crescentes em " + Fmt.component(entry.getKey()))
                    .observation("A taxa de falha subiu de " + Fmt.pct(pb) + " para " + Fmt.pct(pr) + " nas execuções recentes.")
                    .evidence(ev)
                    .correlation("Salto estatisticamente significativo (z ≥ 1,96) — não é oscilação normal do laptop.")
                    .hypothesis("Mudança recente (código, dados de teste ou dependência) introduziu um caminho de erro.")
                    .recommend("Abrir a falha mais recente e comparar com uma execução bem-sucedida anterior.")
                    .recommend("Verificar dependências (LocalStack, filas, tabelas) e dados de entrada recentes.")
                    .component(entry.getKey())
                    .execution(lastFailed != null ? lastFailed.executionId() : null)
                    .build());
        }
        return out;
    }

    private static boolean failed(Execution e) {
        return e.status() != null && e.status().name().equals("FAILED");
    }
}
