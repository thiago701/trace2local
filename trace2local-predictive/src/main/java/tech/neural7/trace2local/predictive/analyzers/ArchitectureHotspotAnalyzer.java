package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.Stats;
import tech.neural7.trace2local.predictive.correlation.Topology;

import java.util.ArrayList;
import java.util.List;

/**
 * ARCH-HOT-001 — HOTSPOT ARQUITETURAL: componente presente na maioria dos
 * fluxos observados E com alto fan-in/fan-out (vizinhos distintos) — "o serviço X
 * participa de 73% dos fluxos e depende de 11 componentes". Recursos AWS de
 * fronteira (tabela/fila) ficam de fora: compartilhar uma tabela é normal; o
 * alvo é CÓDIGO concentrando acoplamento.
 */
public final class ArchitectureHotspotAnalyzer implements PredictiveAnalyzer {

    public static final String ID = "ARCH-HOT-001";

    @Override
    public String name() {
        return "ArchitectureHotspotAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.CORPUS;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        if (ctx.corpus() == null || ctx.corpus().size() < (int) ctx.setting("hotspot.minExecutions", 8)) {
            return List.of();
        }
        Topology topo = Topology.of(ctx.corpus());
        int flows = topo.flows().size();
        if (flows < (int) ctx.setting("hotspot.minFlows", 3)) {
            return List.of();
        }
        double minShare = ctx.setting("hotspot.minShare", 0.6);
        int minNeighbors = (int) ctx.setting("hotspot.minNeighbors", 6);
        List<Insight> out = new ArrayList<>();
        for (Topology.Component c : topo.components().values()) {
            if (!c.zone.equals("core") || c.id.startsWith("http:")) {
                continue; // rotas de entrada são o "rosto" do serviço, não hotspot
            }
            double share = (double) c.flows.size() / flows;
            if (share < minShare || c.neighbors.size() < minNeighbors) {
                continue;
            }
            List<Evidence> ev = new ArrayList<>();
            ev.add(new Evidence(Evidence.Kind.METRIC, "participação nos fluxos", c.flows.size() + " de " + flows
                    + " (" + Fmt.pct(share) + ")", Evidence.Ref.component(c.id)));
            ev.add(new Evidence(Evidence.Kind.METRIC, "componentes vizinhos distintos", String.valueOf(c.neighbors.size()),
                    Evidence.Ref.component(c.id)));
            ev.add(Evidence.metric("chamadas / erros", c.calls + " / " + c.errors));
            ev.add(Evidence.metric("p50 / p95", Fmt.ms(c.p50()) + " / " + Fmt.ms(c.p95())));
            String some = String.join(", ", c.neighbors.stream().limit(6).map(Fmt::component).toList());
            out.add(InsightBuilder.of(ID, name())
                    .subject(c.id)
                    .category(Insight.Category.ARCHITECTURE)
                    .severity(share >= 0.8 && c.neighbors.size() >= 10 ? Insight.Severity.HIGH : Insight.Severity.MEDIUM)
                    .confidence(Stats.evidenceConfidence(topo.executions(), share * c.neighbors.size() / 4.0, 8))
                    .nature(Insight.Nature.CORRELATION)
                    .title(Fmt.component(c.id) + " concentra o ecossistema")
                    .observation(Fmt.component(c.id) + " participa de " + Fmt.pct(share) + " dos fluxos observados e se liga a "
                            + c.neighbors.size() + " componentes.")
                    .evidence(ev)
                    .correlation("Vizinhos: " + some + (c.neighbors.size() > 6 ? "…" : "") + ".")
                    .hypothesis("Acoplamento excessivo ou responsabilidade demais num único ponto — risco de gargalo e de efeito cascata.")
                    .recommend("Avaliar separar responsabilidades por fluxo (bounded contexts) ou introduzir eventos.")
                    .recommend("Garantir testes de contrato e proteção (timeout/retry) nas dependências dele.")
                    .component(c.id)
                    .build());
        }
        return out;
    }
}
