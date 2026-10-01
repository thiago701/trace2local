package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;

import java.util.List;

/**
 * Catálogo inicial (ADR-013 §5). Ordem = custo crescente. Cada analisador só
 * entra aqui depois de passar no benchmark de cenários ({@code PredictiveBenchmarkTest})
 * com precisão/recall/FPR registrados em {@code docs/qa/BENCHMARK-PREDITIVO.md}.
 */
public final class BuiltinAnalyzers {

    private BuiltinAnalyzers() {}

    public static List<PredictiveAnalyzer> all() {
        return List.of(
                new AsyncLatencyAnalyzer(),
                new AsyncConsumerAnalyzer(),
                new DatabaseAccessAnalyzer(),
                new IdempotencyAnalyzer(),
                new ResilienceAnalyzer(),
                new SensitiveDataAnalyzer(),
                new FlowRegressionAnalyzer(),
                new ExecutionOutlierAnalyzer(),
                new ArchitectureHotspotAnalyzer(),
                new ErrorTrendAnalyzer(),
                new TerraformDriftAnalyzer(),
                new CoverageGateAnalyzer());
    }
}
