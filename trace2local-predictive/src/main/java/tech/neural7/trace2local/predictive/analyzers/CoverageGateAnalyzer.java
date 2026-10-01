package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.project.ProjectSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TEST-COV-001 — QUALITY GATE DE COBERTURA quebrado: relatório JaCoCo × exigência
 * declarada (pom {@code jacoco:check}, {@code sonar-project.properties} ou env),
 * com a CONCENTRAÇÃO das linhas não cobertas por pacote ("{@code payment.service}
 * concentra a maior parte"). Só dispara com gate declarado — sem gate não há
 * régua, e cobertura por si só não é achado.
 */
public final class CoverageGateAnalyzer implements PredictiveAnalyzer {

    public static final String ID = "TEST-COV-001";

    @Override
    public String name() {
        return "CoverageGateAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.PROJECT;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        ProjectSnapshot p = ctx.project();
        if (p == null || p.coverage() == null || p.coverageGate() == null) {
            return List.of();
        }
        ProjectSnapshot.Coverage c = p.coverage();
        ProjectSnapshot.Gate g = p.coverageGate();
        double ratio = c.ratio();
        if (ratio + 1e-9 >= g.minimum()) {
            return List.of();
        }
        List<Map.Entry<String, long[]>> byMissed = new ArrayList<>(c.byPackage().entrySet());
        byMissed.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        long missedTotal = Math.max(1, c.missed());
        List<Evidence> ev = new ArrayList<>();
        ev.add(Evidence.file(Evidence.Kind.TEST, "cobertura de linhas (JaCoCo)", Fmt.pct(ratio)
                + " · " + c.covered() + " cobertas / " + c.missed() + " não cobertas", c.report(), 0));
        ev.add(new Evidence(Evidence.Kind.CONFIG, "exigência do gate (" + g.source() + ")", Fmt.pct(g.minimum()),
                g.file() != null ? Evidence.Ref.file(g.file(), g.line()) : null));
        String top = null;
        double topShare = 0;
        for (var e : byMissed.subList(0, Math.min(3, byMissed.size()))) {
            double share = (double) e.getValue()[0] / missedTotal;
            ev.add(new Evidence(Evidence.Kind.TEST, "pacote " + e.getKey(), e.getValue()[0] + " linhas não cobertas ("
                    + Fmt.pct(share) + " do total)", Evidence.Ref.component(e.getKey())));
            if (top == null) {
                top = e.getKey();
                topShare = share;
            }
        }
        long linesToGate = (long) Math.ceil(g.minimum() * (c.covered() + c.missed()) - c.covered());
        return List.of(InsightBuilder.of(ID, name())
                .subject(c.report())
                .category(Insight.Category.TESTS)
                .severity(g.minimum() - ratio >= 0.1 ? Insight.Severity.HIGH : Insight.Severity.MEDIUM)
                .confidence(0.999)
                .nature(Insight.Nature.FACT)
                .title("Cobertura abaixo do Quality Gate")
                .observation("Os testes cobrem " + Fmt.pct(ratio) + " das linhas, enquanto o gate exige " + Fmt.pct(g.minimum()) + ".")
                .evidence(ev)
                .correlation(top != null ? "O pacote " + top + " concentra " + Fmt.pct(topShare) + " das linhas não cobertas; faltam ~"
                        + linesToGate + " linhas para o gate." : null)
                .hypothesis("Código novo entrou sem teste correspondente no pacote mais descoberto.")
                .recommend("Priorizar testes no pacote " + (top != null ? top : "mais descoberto") + " (maior retorno por teste).")
                .recommend("Usar a árvore do Trace2Local para escolher os fluxos reais a cobrir (casos de teste a partir de execuções).")
                .component(top)
                .build());
    }
}
