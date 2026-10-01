package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.correlation.Stats;
import tech.neural7.trace2local.predictive.decision.Answer;
import tech.neural7.trace2local.predictive.history.FlowHistory;
import tech.neural7.trace2local.predictive.project.ProjectSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PERF-ASYNC-001 — latência concentrada entre PUBLICAÇÃO e CONSUMO (fila/tópico).
 *
 * <p>Correlação multidimensional: segmentos do trace (síncrono × espera ×
 * consumidor × banco) + histórico da espera no mesmo fluxo + configuração de
 * concorrência/batch/visibilidade no IaC + {@code Init Duration} do REPORT da
 * Lambda (cold start) nos logs. A causa provável é uma micro-decisão (Jev →
 * regra) — apresentada como HIPÓTESE, nunca como fato.
 */
public final class AsyncLatencyAnalyzer implements PredictiveAnalyzer {

    public static final String ID = "PERF-ASYNC-001";

    @Override
    public String name() {
        return "AsyncLatencyAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.EXECUTION;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        FlowView flow = ctx.flow();
        if (flow == null || flow.endToEndMs() <= 0) {
            return List.of();
        }
        long total = flow.endToEndMs();
        FlowView.Segment worst = null;
        for (FlowView.Segment s : flow.segments()) {
            if (s.kind().equals("queue-wait") && (worst == null || s.durationMs() > worst.durationMs())) {
                worst = s;
            }
        }
        if (worst == null) {
            return List.of();
        }
        long wait = flow.totalQueueWaitMs();
        double share = (double) wait / total;
        double minWait = ctx.setting("async.minWaitMs", 500);
        double minShare = ctx.setting("async.minShare", 0.40);
        // baseline da ESPERA desta fila: todas as execuções anteriores do fluxo (exceto a atual)
        List<Long> previousWaits = ctx.history() == null ? List.of() : ctx.history().samples(flow.flowKey()).stream()
                .filter(x -> !x.executionId().equals(ctx.executionId())).map(FlowHistory.Sample::queueWaitMs).toList();
        double baselineWait = previousWaits.size() >= 5 ? Stats.median(previousWaits) : Double.NaN;
        boolean historicAnomaly = !Double.isNaN(baselineWait) && baselineWait > 0
                && wait >= 3 * baselineWait && wait >= 200;
        if (!((wait >= minWait && share >= minShare) || historicAnomaly)) {
            return List.of();
        }

        String execId = ctx.executionId();
        List<Evidence> evidence = new ArrayList<>();
        for (FlowView.Segment s : flow.segments()) {
            String label = switch (s.kind()) {
                case "sync" -> "entrada (" + s.label() + ")";
                case "queue-wait" -> "espera em fila " + s.label();
                case "consumer" -> "consumidor " + s.label();
                case "db" -> "banco " + s.label();
                case "external" -> "externo " + s.label();
                default -> s.label();
            };
            evidence.add(Evidence.segment(label, Fmt.ms(s.durationMs()), execId, s.nodeId()));
        }
        if (previousWaits.size() >= 3) {
            evidence.add(Evidence.history("espera em fila — mediana histórica do fluxo",
                    Fmt.ms(Stats.median(previousWaits)) + " (" + previousWaits.size() + " execuções)"));
        }
        List<ProjectSnapshot.Assignment> config = queueConfig(ctx.project(), worst.component(), flow);
        for (ProjectSnapshot.Assignment a : config) {
            evidence.add(Evidence.file(Evidence.Kind.IAC, a.key(), a.value(), a.file(), a.line()));
        }
        Double initMs = coldStart(ctx.logs());
        if (initMs != null) {
            evidence.add(new Evidence(Evidence.Kind.LOG, "Init Duration (cold start do consumidor)", Fmt.ms(initMs), null));
        }

        // micro-decisão: causa provável (Jev → regra determinística)
        LinkedHashMap<String, String> causes = new LinkedHashMap<>();
        causes.put("cold-start", "The consumer function was cold-starting (initialization time dominates)");
        causes.put("baixa-concorrencia", "Too few concurrent consumers or a low concurrency/batch configuration");
        causes.put("visibilidade-retry", "Message became visible only after a visibility timeout or retry/redelivery");
        causes.put("backlog-polling", "Backlog or polling interval delayed the consumption");
        String ruleCause;
        String ruleWhy;
        if (initMs != null && initMs >= 0.4 * wait) {
            ruleCause = "cold-start";
            ruleWhy = "Init Duration de " + Fmt.ms(initMs) + " explica boa parte da espera";
        } else if (config.stream().anyMatch(a -> a.key().toLowerCase(Locale.ROOT).contains("concurren"))) {
            ruleCause = "baixa-concorrencia";
            ruleWhy = "há limite de concorrência configurado no IaC";
        } else if (config.stream().anyMatch(a -> a.key().toLowerCase(Locale.ROOT).contains("visibility"))
                && wait > 10_000) {
            ruleCause = "visibilidade-retry";
            ruleWhy = "espera longa compatível com visibility timeout configurado";
        } else {
            ruleCause = "backlog-polling";
            ruleWhy = "sem evidência de cold start nem de limite de concorrência";
        }
        Map<String, String> state = new LinkedHashMap<>();
        state.put("flow", flow.flowKey() + " end-to-end " + total + " ms");
        state.put("segments", segmentsText(flow));
        state.put("config", config.isEmpty() ? "no concurrency/batch/visibility configuration found"
                : String.join("; ", config.stream().map(a -> a.key() + "=" + a.value()).toList()));
        state.put("cold_start", initMs != null ? "consumer Init Duration " + Math.round(initMs) + " ms" : "no cold start observed");
        Answer cause = ctx.decisions().choice("perf_async_cause", state,
                "What is the most likely cause of the delay between publishing and consuming the message?",
                causes, ruleCause, 0.6, ruleWhy);

        String hypothesis = switch (cause.choice() == null ? ruleCause : cause.choice()) {
            case "cold-start" -> "Provável cold start do consumidor: a inicialização da função domina a espera.";
            case "baixa-concorrencia" -> "Possível baixa concorrência de consumidores ou batch size/limite de concorrência inadequado.";
            case "visibilidade-retry" -> "Possível reentrega por visibility timeout/retry antes do consumo efetivo.";
            default -> "Possível backlog na fila ou intervalo de polling do consumidor.";
        };
        Insight.Severity severity = share >= 0.75 && wait >= 2000 ? Insight.Severity.HIGH
                : wait >= 1000 ? Insight.Severity.MEDIUM : Insight.Severity.LOW;
        double confidence = 0.6 + 0.3 * Math.min(1, share) * Math.min(1, wait / 2000.0)
                + (historicAnomaly ? 0.08 : 0);
        InsightBuilder b = InsightBuilder.of(ID, name())
                .subject(flow.flowKey() + "|" + worst.component())
                .category(Insight.Category.PERFORMANCE)
                .severity(severity)
                .confidence(confidence)
                .nature(Insight.Nature.CORRELATION)
                .title("Latência elevada no processamento assíncrono")
                .observation("O fluxo " + Fmt.component(flow.flowKey()) + " leva " + Fmt.ms(total)
                        + " de ponta a ponta; a espera entre publicação e consumo soma " + Fmt.ms(wait) + ".")
                .evidence(evidence)
                .correlation(Fmt.pct(share) + " do tempo total está entre a publicação em "
                        + Fmt.component(worst.component()) + " e o início do consumidor"
                        + (historicAnomaly ? " — " + Math.round(wait / baselineWait) + "× a mediana histórica dessa espera." : "."))
                .hypothesis(hypothesis)
                .recommend("Verificar a concorrência do consumidor (reserved/maximum concurrency).")
                .recommend("Verificar o batch size e o batching window do event source mapping.")
                .recommend("Avaliar o visibility timeout da fila em relação à duração do consumidor.")
                .recommend("Comparar com execuções anteriores na aba Histórico.")
                .component(worst.component())
                .execution(execId)
                .decidedBy(cause.engine());
        flow.steps().stream().filter(s -> s.depth() == 0).findFirst().ifPresent(s -> b.component(s.component()));
        flow.steps().stream().filter(FlowView.Step::asyncConsumer).forEach(s -> b.component(s.component()));
        return List.of(b.build());
    }

    private static String segmentsText(FlowView flow) {
        StringBuilder sb = new StringBuilder();
        for (FlowView.Segment s : flow.segments()) {
            sb.append(s.kind()).append(' ').append(s.label()).append(' ').append(s.durationMs()).append(" ms; ");
        }
        return sb.toString();
    }

    /** Configuração de fila/consumidor no IaC (batch, concorrência, visibilidade). */
    static List<ProjectSnapshot.Assignment> queueConfig(ProjectSnapshot project, String queueComponent, FlowView flow) {
        List<ProjectSnapshot.Assignment> out = new ArrayList<>();
        if (project == null) {
            return out;
        }
        for (ProjectSnapshot.Assignment a : project.timeouts()) {
            String k = a.key().toLowerCase(Locale.ROOT);
            if (k.contains("batch") || k.contains("concurren") || k.contains("visibility")
                    || k.contains("maximum_batching_window")) {
                out.add(a);
            }
            if (out.size() >= 4) {
                break;
            }
        }
        return out;
    }

    /** Maior Init Duration nos REPORT do trace (cold start), em ms. */
    static Double coldStart(List<LogEntry> logs) {
        if (logs == null) {
            return null;
        }
        Double max = null;
        for (LogEntry l : logs) {
            String m = l.message();
            if (m != null && m.startsWith("REPORT RequestId:")) {
                int i = m.indexOf("Init Duration:");
                if (i >= 0) {
                    String rest = m.substring(i + "Init Duration:".length()).trim();
                    String num = rest.split("\\s")[0];
                    try {
                        double v = Double.parseDouble(num);
                        max = max == null ? v : Math.max(max, v);
                    } catch (NumberFormatException ignored) {
                        // formato inesperado
                    }
                }
            }
        }
        return max;
    }
}
