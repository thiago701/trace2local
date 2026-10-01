package tech.neural7.trace2local.predictive.api;

import java.util.List;

/**
 * SPI de analisador preditivo (ADR-013). Novos detectores entram como classes
 * que implementam esta interface — registradas por {@link java.util.ServiceLoader}
 * ({@code META-INF/services/tech.neural7.trace2local.predictive.api.PredictiveAnalyzer})
 * ou programaticamente — SEM tocar no core nem no pipeline.
 *
 * <p>Contrato:
 * <ul>
 *   <li>roda FORA do caminho de ingest, em worker do pipeline, com timeout;</li>
 *   <li>é puro em relação ao contexto: não faz I/O de rede; arquivos do projeto
 *       chegam já lidos em {@link AnalysisContext#project()};</li>
 *   <li>micro-decisões semânticas vão por {@link AnalysisContext#decisions()}
 *       (Jev → determinístico), nunca por chamada direta a LLM;</li>
 *   <li>todo insight carrega evidência navegável e natureza declarada.</li>
 * </ul>
 */
public interface PredictiveAnalyzer {

    /** Nome estável (aparece no insight e no benchmark). */
    String name();

    /** Gatilho: por execução concluída, ou por varredura do projeto/histórico. */
    Scope scope();

    /** Achados (pode ser vazio — silêncio é a resposta certa na maioria das vezes). */
    List<Insight> analyze(AnalysisContext context);

    enum Scope {
        /** Uma execução concluída (com histórico disponível para comparação). */
        EXECUTION,
        /** Acervo + histórico (topologia, hotspots, regressões agregadas). */
        CORPUS,
        /** Artefatos do projeto (Terraform, cobertura, configuração). */
        PROJECT
    }
}
