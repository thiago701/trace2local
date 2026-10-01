package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.otel.OtelAttributeNames;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.project.ProjectSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resiliência no caminho crítico (trace × código × configuração × logs):
 * <ul>
 *   <li><b>RES-001</b> — chamada EXTERNA no caminho crítico sem evidência de
 *       timeout/retry/circuit breaker na classe que a origina nem na configuração
 *       (ausência de evidência ≠ evidência de ausência: natureza HIPÓTESE);</li>
 *   <li><b>RES-002</b> — TIMEOUT: erro com assinatura de timeout ou
 *       {@code Task timed out} no REPORT/log, ou duração ≥ 90% do timeout
 *       configurado no IaC — FATO.</li>
 * </ul>
 */
public final class ResilienceAnalyzer implements PredictiveAnalyzer {

    public static final String MISSING_ID = "RES-001";
    public static final String TIMEOUT_ID = "RES-002";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "localstack", "host.docker.internal");

    @Override
    public String name() {
        return "ResilienceAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.EXECUTION;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        FlowView flow = ctx.flow();
        if (flow == null) {
            return List.of();
        }
        List<Insight> out = new ArrayList<>();
        missingProtection(ctx, flow).ifPresent(out::add);
        timeouts(ctx, flow).ifPresent(out::add);
        return out;
    }

    private java.util.Optional<Insight> missingProtection(AnalysisContext ctx, FlowView flow) {
        List<FlowView.Step> critical = flow.criticalPath();
        for (FlowView.Step s : flow.steps()) {
            if (s.kind() != NodeKind.HTTP_CLIENT) {
                continue;
            }
            Map<String, String> a = s.node().attributes() != null ? s.node().attributes() : Map.of();
            String host = a.getOrDefault(OtelAttributeNames.SERVER_ADDRESS, "").toLowerCase(Locale.ROOT);
            if (LOCAL_HOSTS.contains(host)) {
                continue;
            }
            String owner = owningClass(flow, s);
            ProjectSnapshot project = ctx.project();
            List<ProjectSnapshot.Marker> markers = owner != null && project != null
                    ? project.resilience().getOrDefault(owner, List.of()) : List.of();
            boolean configTimeout = project != null && project.timeouts().stream().anyMatch(t ->
                    t.key().toLowerCase(Locale.ROOT).contains("timeout")
                            && (host.isBlank() || t.file().contains("application") || t.key().toLowerCase(Locale.ROOT).contains(host)));
            if (!markers.isEmpty() || configTimeout) {
                continue;
            }
            boolean onCritical = critical.contains(s);
            double share = flow.endToEndMs() > 0 ? (double) s.durationMs() / flow.endToEndMs() : 0;
            List<Evidence> ev = new ArrayList<>();
            ev.add(Evidence.span("chamada externa " + s.node().label(), Fmt.ms(s.durationMs()) + " · "
                    + Fmt.pct(share) + " do fluxo", ctx.executionId(), s.node().nodeId()));
            if (owner != null && project != null && project.sources().containsKey(owner)) {
                ProjectSnapshot.Location loc = project.sources().get(owner);
                ev.add(Evidence.file(Evidence.Kind.CODE, "classe de origem " + owner, "sem @Retry/@CircuitBreaker/timeout",
                        loc.file(), loc.line()));
            }
            ev.add(Evidence.metric("configuração varrida", project == null ? "projeto não varrido"
                    : project.timeouts().size() + " chave(s) de timeout/retry encontradas, nenhuma para " + (host.isBlank() ? "este cliente" : host)));
            return java.util.Optional.of(InsightBuilder.of(MISSING_ID, name())
                    .subject(s.component())
                    .category(Insight.Category.RESILIENCE)
                    .severity(onCritical ? Insight.Severity.MEDIUM : Insight.Severity.LOW)
                    .confidence(project == null || project.sources().isEmpty() ? 0.45 : (onCritical ? 0.7 : 0.6))
                    .nature(Insight.Nature.HYPOTHESIS)
                    .title("Chamada externa sem proteção aparente" + (onCritical ? " no caminho crítico" : ""))
                    .observation("A chamada a " + (host.isBlank() ? s.node().label() : host) + " participa "
                            + (onCritical ? "do caminho crítico" : "do fluxo") + " e não foram identificadas evidências de timeout, retry ou circuit breaker.")
                    .evidence(ev)
                    .correlation(onCritical ? "Qualquer lentidão do parceiro vira lentidão do fluxo inteiro (caminho crítico)." : null)
                    .hypothesis("Sem timeout explícito, o cliente herda padrões longos; sem retry/circuit breaker, falhas transitórias derrubam a operação.")
                    .recommend("Definir connect/read timeout explícitos no cliente HTTP.")
                    .recommend("Envolver a chamada com retry exponencial + circuit breaker (Resilience4j).")
                    .recommend("Se já existe proteção fora do código varrido, marque como esperado para calibrar o analisador.")
                    .component(s.component())
                    .execution(ctx.executionId())
                    .build());
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<Insight> timeouts(AnalysisContext ctx, FlowView flow) {
        List<Evidence> ev = new ArrayList<>();
        String component = null;
        for (FlowView.Step s : flow.steps()) {
            if (s.failed() && s.node().error() != null) {
                String sig = (s.node().error().type() + " " + s.node().error().message()).toLowerCase(Locale.ROOT);
                if (sig.contains("timeout") || sig.contains("timed out")) {
                    ev.add(Evidence.span("erro de timeout em " + s.node().label(), s.node().error().type() + " após "
                            + Fmt.ms(s.durationMs()), ctx.executionId(), s.node().nodeId()));
                    component = s.component();
                }
            }
        }
        if (ctx.logs() != null) {
            for (LogEntry l : ctx.logs()) {
                if (l.message() != null && l.message().contains("Task timed out")) {
                    ev.add(new Evidence(Evidence.Kind.LOG, "CloudWatch: " + l.logGroup(), l.message(),
                            new Evidence.Ref(ctx.executionId(), null, null, 0, l.logGroup())));
                }
            }
        }
        // duração ≥ 90% do timeout configurado (Lambda timeout no IaC)
        if (ctx.project() != null) {
            for (FlowView.Step s : flow.steps()) {
                if (s.kind() != NodeKind.LAMBDA) {
                    continue;
                }
                String fn = s.node().attributes() != null ? s.node().attributes().getOrDefault(OtelAttributeNames.FAAS_NAME, "") : "";
                List<ProjectSnapshot.Assignment> lambdaTimeouts = ctx.project().timeouts().stream()
                        .filter(t -> (t.key().equalsIgnoreCase("timeout") || t.key().toLowerCase(Locale.ROOT).endsWith(".timeout"))
                                && t.file().endsWith(".tf"))
                        .toList();
                for (ProjectSnapshot.Assignment t : lambdaTimeouts) {
                    // só atribui o timeout à função quando é inequívoco (um só no IaC, ou o arquivo cita a função)
                    if (lambdaTimeouts.size() > 1 && (fn.isBlank() || !t.file().contains(fn))) {
                        continue;
                    }
                    try {
                        double seconds = Double.parseDouble(t.value().replaceAll("[^0-9.]", ""));
                        if (seconds > 0 && s.durationMs() >= 0.9 * seconds * 1000) {
                            ev.add(Evidence.file(Evidence.Kind.IAC, "timeout configurado", t.value() + " s", t.file(), t.line()));
                            ev.add(Evidence.span("duração da função " + fn, Fmt.ms(s.durationMs()), ctx.executionId(), s.node().nodeId()));
                            component = s.component();
                            break;
                        }
                    } catch (NumberFormatException ignored) {
                        // valor não numérico (variável)
                    }
                }
            }
        }
        if (ev.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(InsightBuilder.of(TIMEOUT_ID, name())
                .subject(component != null ? component : flow.flowKey())
                .category(Insight.Category.RESILIENCE)
                .severity(Insight.Severity.HIGH)
                .confidence(0.999)
                .nature(Insight.Nature.FACT)
                .title("Timeout no fluxo " + Fmt.component(flow.flowKey()))
                .observation("A operação atingiu (ou chegou a ≥ 90% de) um limite de tempo.")
                .evidence(ev)
                .correlation("Timeout no caminho do fluxo encerra a operação sem resultado — o cliente pode reenviar e duplicar efeito.")
                .hypothesis("Timeout inadequado para o volume/latência real, ou dependência lenta sem proteção.")
                .recommend("Comparar a duração real (p95) com o timeout configurado e ajustar com margem.")
                .recommend("Garantir idempotência do efeito antes de aumentar retries.")
                .component(component)
                .execution(ctx.executionId())
                .build());
    }

    /** Classe de origem: atributo {@code code.namespace} do passo ou de um ancestral. */
    static String owningClass(FlowView flow, FlowView.Step s) {
        FlowView.Step cur = s;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            Map<String, String> a = cur.node().attributes() != null ? cur.node().attributes() : Map.of();
            String ns = a.get(OtelAttributeNames.CODE_NAMESPACE);
            if (ns != null && !ns.isBlank()) {
                int i = ns.lastIndexOf('.');
                return i >= 0 ? ns.substring(i + 1) : ns;
            }
            cur = cur.parentId() != null ? flow.step(cur.parentId()) : null;
        }
        return null;
    }
}
