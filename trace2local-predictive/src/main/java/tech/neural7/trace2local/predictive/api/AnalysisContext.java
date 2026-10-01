package tech.neural7.trace2local.predictive.api;

import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.decision.DecisionPort;
import tech.neural7.trace2local.predictive.history.FlowHistory;
import tech.neural7.trace2local.predictive.project.ProjectSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Tudo que um analisador pode consultar — já coletado, imutável durante a
 * análise e sem I/O (ADR-013): a execução e sua visão correlacionada, os logs,
 * o acervo recente (para correlação entre execuções), o histórico/baseline, os
 * artefatos do projeto e a porta de micro-decisões (Jev → determinístico).
 *
 * @param execution  execução analisada ({@code null} nos escopos CORPUS/PROJECT)
 * @param flow       visão correlacionada da execução ({@code null} idem)
 * @param logs       logs correlacionados da execução
 * @param corpus     execuções recentes concluídas (mais nova primeiro)
 * @param history    histórico local de fluxos
 * @param project    artefatos do projeto
 * @param decisions  micro-decisões tipadas
 * @param settings   limiares configuráveis (nome → valor)
 */
public record AnalysisContext(
        Execution execution,
        FlowView flow,
        List<LogEntry> logs,
        List<Execution> corpus,
        FlowHistory history,
        ProjectSnapshot project,
        DecisionPort decisions,
        Map<String, Double> settings) {

    /** Limiar configurável com padrão. */
    public double setting(String name, double fallback) {
        Double v = settings != null ? settings.get(name) : null;
        return v != null ? v : fallback;
    }

    public String executionId() {
        return execution != null ? execution.executionId() : null;
    }
}
