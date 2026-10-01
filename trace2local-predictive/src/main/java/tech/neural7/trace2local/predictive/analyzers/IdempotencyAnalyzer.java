package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.MutationKind;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.decision.Answer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Idempotência observada em runtime (dado + trace + acervo):
 * <ul>
 *   <li><b>IDEM-001</b> — SOBRESCRITA: uma criação ({@code PutItem}) encontrou o
 *       item JÁ existente (o delta EXACT traz o {@code before}) e o substituiu sem
 *       guarda condicional — o mesmo evento/entidade foi processado de novo;</li>
 *   <li><b>IDEM-002</b> — PROCESSAMENTO DUPLICADO: o mesmo consumidor aplicou
 *       efeito sobre a MESMA entidade em execuções diferentes da janela recente.</li>
 * </ul>
 * Fluxos com guarda comprovada ({@code ConditionalCheckFailed} na mesma chave)
 * são considerados protegidos — sem ruído.
 */
public final class IdempotencyAnalyzer implements PredictiveAnalyzer {

    public static final String OVERWRITE_ID = "IDEM-001";
    public static final String DUPLICATE_ID = "IDEM-002";
    private static final List<String> MONEY = List.of("pag", "pay", "bill", "cobr", "charge", "invoice", "fatur",
            "order", "pedido", "pix", "transf", "saldo", "balance", "estorn", "refund");

    @Override
    public String name() {
        return "IdempotencyAnalyzer";
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
        overwrite(ctx, flow).ifPresent(out::add);
        duplicate(ctx, flow).ifPresent(out::add);
        return out;
    }

    private java.util.Optional<Insight> overwrite(AnalysisContext ctx, FlowView flow) {
        List<FlowView.Step> overwritten = new ArrayList<>();
        for (FlowView.Step s : flow.steps()) {
            DataMutation m = s.node().mutation();
            if (m != null && m.kind() == MutationKind.CREATE && hadPriorState(m.before()) && !s.failed()) {
                overwritten.add(s);
            }
        }
        if (overwritten.isEmpty()) {
            return java.util.Optional.empty();
        }
        FlowView.Step first = overwritten.get(0);
        DataMutation m = first.node().mutation();
        List<Evidence> ev = new ArrayList<>();
        for (FlowView.Step s : overwritten) {
            ev.add(new Evidence(Evidence.Kind.DATA, "PutItem sobre item existente " + s.node().mutation().key(),
                    s.node().mutation().deltas() == null || s.node().mutation().deltas().isEmpty()
                            ? "conteúdo idêntico regravado" : s.node().mutation().deltas().size() + " campo(s) alterado(s)",
                    Evidence.Ref.node(ctx.executionId(), s.node().nodeId())));
        }
        // quem criou antes? (correlação com o acervo)
        String previous = previousWriter(ctx, m.target(), m.key());
        if (previous != null) {
            ev.add(new Evidence(Evidence.Kind.SPAN, "criação anterior da mesma chave", previous, Evidence.Ref.execution(previous)));
        }
        Answer harm = harmful(ctx, flow, first.component());
        boolean identical = m.deltas() == null || m.deltas().isEmpty();
        return java.util.Optional.of(InsightBuilder.of(OVERWRITE_ID, name())
                .subject(flow.flowKey() + "|" + first.component())
                .category(Insight.Category.DATA)
                .severity(harm.yes() && !identical ? Insight.Severity.HIGH : Insight.Severity.MEDIUM)
                .confidence(0.999)
                .nature(Insight.Nature.FACT)
                .title("Criação sobrescreveu um registro existente")
                .observation("Em " + Fmt.component(first.component()) + ", a chave " + m.key()
                        + " já existia e foi regravada sem condição" + (identical ? " (mesmo conteúdo)." : " — com valores diferentes."))
                .evidence(ev)
                .correlation(previous != null ? "A mesma chave foi criada antes na execução " + previous
                        + ": o mesmo evento/entidade passou pelo fluxo mais de uma vez." : "O delta EXACT mostra o estado anterior do item.")
                .hypothesis(harm.yes()
                        ? "Sem guarda de idempotência, um reprocessamento pode duplicar efeito de negócio (" + harm.rationale() + ")."
                        : "Reprocessamento hoje inofensivo, mas sem guarda explícita.")
                .recommend("Gravar com ConditionExpression attribute_not_exists(pk) (ou chave de idempotência).")
                .recommend("Registrar a chave de idempotência do evento antes de aplicar efeitos colaterais.")
                .component(first.component())
                .execution(ctx.executionId())
                .decidedBy(harm.engine())
                .build());
    }

    /**
     * "Havia item antes?" — {@code null}, ausente, objeto/array VAZIO ou texto em
     * branco significam NÃO (o interceptor real do DynamoDB devolve {@code {}} para
     * chave nova; descoberto no caso Lambda + LocalStack, era falso positivo).
     */
    static boolean hadPriorState(com.fasterxml.jackson.databind.JsonNode before) {
        if (before == null || before.isNull() || before.isMissingNode()) {
            return false;
        }
        if (before.isContainerNode()) {
            return before.size() > 0;
        }
        return !(before.isTextual() && before.asText().isBlank());
    }

    private java.util.Optional<Insight> duplicate(AnalysisContext ctx, FlowView flow) {
        // efeitos (UPDATE/CREATE) aplicados por consumidores assíncronos nesta execução
        Map<String, FlowView.Step> effects = new LinkedHashMap<>();
        for (FlowView.Step s : flow.steps()) {
            DataMutation m = s.node().mutation();
            if (m == null || !concreteKey(m) || m.kind() == MutationKind.READ_ONLY || s.failed()) {
                continue;
            }
            String consumer = consumerOf(flow, s);
            if (consumer != null) {
                effects.put(consumer + "|" + m.target() + "|" + m.key(), s);
            }
        }
        if (effects.isEmpty() || ctx.corpus() == null) {
            return java.util.Optional.empty();
        }
        long windowMs = (long) ctx.setting("idempotency.windowMs", 30 * 60_000);
        for (Map.Entry<String, FlowView.Step> e : effects.entrySet()) {
            for (Execution other : ctx.corpus()) {
                if (other.executionId().equals(ctx.executionId()) || other.startedAt() == null
                        || ctx.execution().startedAt() == null
                        || Math.abs(Duration.between(other.startedAt(), ctx.execution().startedAt()).toMillis()) > windowMs) {
                    continue;
                }
                FlowView of = FlowView.of(other);
                if (guarded(of)) {
                    continue;
                }
                for (FlowView.Step s : of.steps()) {
                    DataMutation m = s.node().mutation();
                    if (m == null || !concreteKey(m) || m.kind() == MutationKind.READ_ONLY || s.failed()) {
                        continue;
                    }
                    String consumer = consumerOf(of, s);
                    if (consumer != null && e.getKey().equals(consumer + "|" + m.target() + "|" + m.key())) {
                        FlowView.Step mine = e.getValue();
                        Answer harm = harmful(ctx, flow, consumer);
                        List<Evidence> ev = List.of(
                                Evidence.span("efeito nesta execução", mine.node().label() + " " + m.key(),
                                        ctx.executionId(), mine.node().nodeId()),
                                Evidence.span("mesmo efeito em outra execução", s.node().label() + " " + m.key(),
                                        other.executionId(), s.node().nodeId()),
                                Evidence.metric("intervalo entre os processamentos",
                                        Fmt.ms(Math.abs(Duration.between(other.startedAt(), ctx.execution().startedAt()).toMillis()))));
                        return java.util.Optional.of(InsightBuilder.of(DUPLICATE_ID, name())
                                .subject(consumer + "|" + m.target())
                                .category(Insight.Category.RESILIENCE)
                                .severity(harm.yes() ? Insight.Severity.HIGH : Insight.Severity.MEDIUM)
                                .confidence(0.85)
                                .nature(Insight.Nature.CORRELATION)
                                .title("Mesma entidade processada mais de uma vez por " + Fmt.component(consumer))
                                .observation("O consumidor " + Fmt.component(consumer) + " aplicou efeito em " + m.target()
                                        + "/" + m.key() + " em duas execuções distintas.")
                                .evidence(ev)
                                .correlation("Mesma chave, mesmo consumidor, janela de " + Fmt.ms(windowMs)
                                        + " — e nenhuma guarda condicional observada.")
                                .hypothesis("Reentrega da mensagem (at-least-once) ou publicação duplicada sem chave de idempotência.")
                                .recommend("Adicionar chave de idempotência por mensagem (tabela de deduplicação com TTL).")
                                .recommend("Usar escrita condicional no efeito de negócio (ex.: status = PENDING).")
                                .recommend("Verificar visibility timeout × duração do consumidor (reentrega).")
                                .component(consumer)
                                .component("dynamodb:" + m.target())
                                .execution(ctx.executionId())
                                .execution(other.executionId())
                                .decidedBy(harm.engine())
                                .build());
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** O passo está sob um consumidor assíncrono? Devolve o componente do consumidor. */
    static String consumerOf(FlowView flow, FlowView.Step s) {
        FlowView.Step cur = s;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            if (cur.asyncConsumer()) {
                return cur.component();
            }
            cur = cur.parentId() != null ? flow.step(cur.parentId()) : null;
        }
        return null;
    }

    /** Execução com guarda condicional comprovada (ConditionalCheckFailed). */
    static boolean guarded(FlowView flow) {
        return flow.steps().stream().anyMatch(s -> s.failed() && s.kind() == NodeKind.DYNAMODB
                && s.node().error() != null && String.valueOf(s.node().error().type()).contains("ConditionalCheckFailed"));
    }

    /** Bind de SQL parametrizado: "?", "$1", ":nome" — o texto é um MOLDE, não a identidade da entidade. */
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\?|\\$\\d+|(?<![:\\w]):[A-Za-z_]\\w*");

    /**
     * A chave identifica a entidade? Δ inferido do SQL guarda o WHERE; com placeholders, duas
     * transferências diferentes têm a mesma "chave" (falso IDEM-002 visto na stack alvo).
     */
    static boolean concreteKey(DataMutation m) {
        if (m.key() == null || m.key().isBlank()) {
            return false;
        }
        return m.fidelity() != tech.neural7.trace2local.model.MutationFidelity.INFERRED
                || !PLACEHOLDER.matcher(m.key()).find();
    }

    private String previousWriter(AnalysisContext ctx, String target, String key) {
        if (ctx.corpus() == null || key == null) {
            return null;
        }
        for (Execution e : ctx.corpus()) {
            if (e.executionId().equals(ctx.executionId())) {
                continue;
            }
            for (FlowView.Step s : FlowView.of(e).steps()) {
                DataMutation m = s.node().mutation();
                if (m != null && m.kind() == MutationKind.CREATE && key.equals(m.key())
                        && (target == null || target.equals(m.target()))) {
                    return e.executionId();
                }
            }
        }
        return null;
    }

    /** Micro-decisão: duplicar este efeito causa dano de negócio? (Jev → léxico de domínio). */
    private Answer harmful(AnalysisContext ctx, FlowView flow, String component) {
        String comp = component.toLowerCase(Locale.ROOT);
        boolean money = MONEY.stream().anyMatch(comp::contains)
                || MONEY.stream().anyMatch(w -> flow.flowKey().toLowerCase(Locale.ROOT).contains(w));
        Map<String, String> state = new LinkedHashMap<>();
        state.put("flow", flow.flowKey());
        state.put("component", component);
        state.put("components", String.join(", ", flow.steps().stream().map(FlowView.Step::component).distinct().toList()));
        return ctx.decisions().noul("idem_harm", state,
                "Processing the same entity twice in this component causes a duplicated business effect "
                        + "(for example charging, billing, ordering or notifying twice).",
                money ? 0.85 : 0.35, money ? "componente lida com dinheiro/pedido" : "efeito sem vocabulário financeiro");
    }
}
