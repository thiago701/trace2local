package tech.neural7.trace2local.mocks.advisor;

import java.util.List;
import java.util.Map;

/**
 * Sugestão do conselheiro: QUANDO plugar um mock e QUAIS variações de resposta validar.
 * Segue o Evidence First do projeto: toda sugestão aponta execuções/nós concretos.
 *
 * @param kind          {@code UNAVAILABLE_DEPENDENCY}, {@code RESPONSE_DRIVES_FLOW}, {@code HAPPY_PATH_ONLY},
 *                      {@code SLOW_DEPENDENCY}, {@code CONTRACT_DRIFT}
 * @param bindingName   nome do binding que "Aplicar" cria/atualiza
 * @param bindingConfig config plano pronto (sem variações) — editável antes de aplicar
 * @param activeBinding binding que já cobre o alvo (se houver)
 */
public record MockSuggestion(String id, String kind, String severity, String title, String why, String api,
                             String target, String operation, List<Evidence> evidence, List<Variation> variations,
                             String bindingName, Map<String, String> bindingConfig, List<String> howTo,
                             String activeBinding) {

    /** Execução/nó que sustenta a sugestão. */
    public record Evidence(String executionId, String nodeId, String text) {}

    /**
     * Variação de resposta pronta para aplicar.
     *
     * @param category  {@code BRANCH} (valor que muda o fluxo), {@code CONTRACT} (previsto no contrato),
     *                  {@code EDGE} (campo ausente/nulo/extra), {@code RESILIENCE} (erro, timeout, falha de rede)
     * @param steps     transformações (tipo + config) aplicadas em ordem
     * @param predicate predicado próprio (tipo + config), ex.: call-count para "falha só na 1ª chamada"
     * @param operation restringe à operação (método + rota) — vira predicado path-matches se não houver outro
     */
    public record Variation(String id, String title, String category, String rationale, List<Step> steps,
                            Map<String, String> predicate, String operation) {}

    public record Step(String type, Map<String, String> props) {}
}
