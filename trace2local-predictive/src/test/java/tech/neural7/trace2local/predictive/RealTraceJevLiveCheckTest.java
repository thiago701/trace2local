package tech.neural7.trace2local.predictive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.predictive.assistant.ExecutionAssistant;
import tech.neural7.trace2local.predictive.decision.DecisionEngine;
import tech.neural7.trace2local.predictive.decision.IntelligenceConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jev AO VIVO sobre os TRACES REAIS (Lambda + LocalStack) — só roda com chave
 * ({@code JEV_API_KEY}/{@code TRACE2LOCAL_JEV_API_KEY}); sem chave é ignorado.
 * Egress estrutural (padrão): só estado minimizado e redigido sai da máquina.
 *
 * <p>Compara, por família de micro-decisão, o laudo FINAL (cascata Jev + política
 * de fusão) com o laudo só-determinístico e registra onde o Jev decidiu, onde a
 * política manteve a regra e o custo. Relatório: {@code target/real-traces-jev.md}.
 */
class RealTraceJevLiveCheckTest {

    @Test
    void jevOnRealTraces() throws Exception {
        String key = System.getenv("JEV_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("TRACE2LOCAL_JEV_API_KEY");
        }
        Assumptions.assumeTrue(key != null && !key.isBlank(), "sem chave Jev — verificação ao vivo ignorada");
        Map<String, String> live = new LinkedHashMap<>();
        live.put("api-key", key);
        live.put("mode", "live");
        live.put("max-questions", "40");
        DecisionEngine jev = new DecisionEngine(IntelligenceConfig.from(live::get));
        DecisionEngine det = new DecisionEngine(IntelligenceConfig.from(k -> null));
        ExecutionAssistant withJev = new ExecutionAssistant(jev);
        ExecutionAssistant onlyRules = new ExecutionAssistant(det);

        List<RealTraceRegressionTest.Captured> all = new ArrayList<>(RealTraceRegressionTest.load("real-traces/lambda-sqs/run-1-fresh"));
        all.addAll(RealTraceRegressionTest.load("real-traces/lambda-sqs/run-2-reprocess"));

        // família → [decisões, iguais ao determinístico, decididas pelo Jev, Jev divergiu e a política manteve a regra]
        Map<String, int[]> fam = new LinkedHashMap<>();
        List<String> divergences = new ArrayList<>();
        long t0 = System.nanoTime();
        for (RealTraceRegressionTest.Captured c : all) {
            ObjectNode a = withJev.analyze(c.execution(), c.logs(), Map.of());
            ObjectNode b = onlyRules.analyze(c.execution(), c.logs(), Map.of());
            String where = c.execution().roots().get(0).label() + "/" + c.execution().status();
            compare("desfecho", a.path("executive").path("outcome"), b.path("executive").path("outcome"), fam, divergences, where);
            compare("prontidão", a.path("executive").path("readiness"), b.path("executive").path("readiness"), fam, divergences, where);
            compare("risco", a.path("executive").path("risk"), b.path("executive").path("risk"), fam, divergences, where);
            a.path("technical").path("roles").fields().forEachRemaining(e ->
                    compare("papel do passo", e.getValue(), b.path("technical").path("roles").path(e.getKey()), fam, divergences, where));
            a.path("logs").fields().forEachRemaining(e ->
                    compare("classe de log", e.getValue(), b.path("logs").path(e.getKey()), fam, divergences, where));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> st = jev.status();

        StringBuilder sb = new StringBuilder("# Jev ao vivo sobre traces REAIS (Lambda + LocalStack)\n\n");
        sb.append(all.size()).append(" execuções reais · ").append(ms).append(" ms · modelo ").append(st.get("model"))
                .append(" · chamadas ").append(st.get("liveCalls")).append(" · falhas ").append(st.get("liveFailures"))
                .append(" · latência média ").append(st.get("avgLatencyMs")).append(" ms · tokens de entrada ")
                .append(st.get("inputTokensToday")).append(" · custo estimado US$ ").append(st.get("estimatedCostTodayUsd")).append("\n\n");
        sb.append("| família | decisões | laudo final = só regras | decididas pelo Jev | Jev divergiu, regra mantida |\n|---|---|---|---|---|\n");
        fam.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" | ")
                .append(v[2]).append(" | ").append(v[3]).append(" |\n"));
        sb.append("\n## Divergências (amostra)\n\n");
        divergences.stream().limit(40).forEach(d -> sb.append("- ").append(d).append('\n'));
        Path out = Path.of("target", "real-traces-jev.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb);
        // o laudo executivo dos traces reais não pode piorar com o Jev ligado (política RULE_FIRST no desfecho)
        assertThat(fam.get("desfecho")[1]).isEqualTo(fam.get("desfecho")[0]);
    }

    private static void compare(String family, JsonNode finalD, JsonNode rulesD, Map<String, int[]> fam, List<String> div, String where) {
        if (finalD == null || finalD.isMissingNode() || rulesD == null || rulesD.isMissingNode()) {
            return;
        }
        int[] f = fam.computeIfAbsent(family, k -> new int[4]);
        f[0]++;
        String a = finalD.path("choice").asText(), b = rulesD.path("choice").asText();
        if (a.equals(b)) {
            f[1]++;
        }
        String engine = finalD.path("engine").asText();
        if (engine.startsWith("jev") && !engine.equals("jev-deterministic") || engine.equals("fusion")) {
            f[2]++;
        }
        JsonNode alt = finalD.path("alternative");
        if (alt.isObject() && alt.path("engine").asText().equals("jev") && !alt.path("choice").asText().equals(a)) {
            f[3]++;
            div.add(family + " em " + where + ": regra '" + a + "' × Jev '" + alt.path("choice").asText() + "' ("
                    + Math.round(alt.path("confidence").asDouble() * 100) + "%)");
        } else if (!a.equals(b)) {
            div.add(family + " em " + where + ": Jev decidiu '" + a + "' (" + Math.round(finalD.path("confidence").asDouble() * 100)
                    + "%) onde a regra diria '" + b + "'");
        }
    }
}
