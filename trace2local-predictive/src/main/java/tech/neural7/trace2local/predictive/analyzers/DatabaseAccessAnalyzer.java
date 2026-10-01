package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.otel.OtelAttributeNames;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Fmt;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Acesso a banco dentro de UMA requisição:
 * <ul>
 *   <li><b>DB-N1-001</b> — N+1: sob o mesmo pai, N chamadas da mesma operação na
 *       mesma tabela com chaves DIFERENTES (opcionalmente precedidas da consulta
 *       "1" que trouxe a lista) — "121 consultas para recuperar 120 entidades";</li>
 *   <li><b>DB-RED-001</b> — consulta REDUNDANTE: a MESMA leitura (tabela +
 *       operação + chave/SQL) repetida na mesma execução.</li>
 * </ul>
 * Fato (contagem) + correlação (padrão) — a hipótese diz o que provavelmente
 * mudar (batch/IN/join/cache).
 */
public final class DatabaseAccessAnalyzer implements PredictiveAnalyzer {

    public static final String N1_ID = "DB-N1-001";
    public static final String REDUNDANT_ID = "DB-RED-001";
    private static final Pattern LITERALS = Pattern.compile("('[^']*'|\\b\\d+\\b)");
    private static final Set<String> READS = Set.of("getitem", "query", "scan", "batchgetitem", "select", "find", "get");

    @Override
    public String name() {
        return "DatabaseAccessAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.EXECUTION;
    }

    private record Call(FlowView.Step step, String component, String operation, String key, String statementShape) {}

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        FlowView flow = ctx.flow();
        if (flow == null) {
            return List.of();
        }
        Map<String, List<Call>> byGroup = new LinkedHashMap<>();
        for (FlowView.Step s : flow.steps()) {
            if (s.kind() != NodeKind.DYNAMODB && s.kind() != NodeKind.SQL) {
                continue;
            }
            Node n = s.node();
            Map<String, String> a = n.attributes() != null ? n.attributes() : Map.of();
            String op = a.getOrDefault(OtelAttributeNames.RPC_METHOD, a.getOrDefault(OtelAttributeNames.DB_OPERATION, "?"));
            String sql = a.getOrDefault(OtelAttributeNames.DB_QUERY_TEXT, a.getOrDefault(OtelAttributeNames.DB_STATEMENT, ""));
            String key = n.mutation() != null && n.mutation().key() != null ? n.mutation().key() : (sql.isBlank() ? null : sql);
            String shape = sql.isBlank() ? op : LITERALS.matcher(sql).replaceAll("?");
            Call call = new Call(s, s.component(), op, key, shape);
            byGroup.computeIfAbsent(s.parentId() + "|" + s.component() + "|" + shape, k -> new ArrayList<>()).add(call);
        }
        List<Insight> out = new ArrayList<>();
        int minN1 = (int) ctx.setting("n1.minCalls", 8);
        Set<Call> inLoops = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<Call> calls : byGroup.values()) {
            Call first = calls.get(0);
            Set<String> distinctKeys = new LinkedHashSet<>();
            calls.forEach(c -> distinctKeys.add(c.key() == null ? "∅" + System.identityHashCode(c) : c.key()));
            long totalMs = calls.stream().mapToLong(c -> c.step().durationMs()).sum();
            if (calls.size() >= minN1 && distinctKeys.size() >= calls.size() * 0.8) {
                out.add(nPlusOne(ctx, flow, calls, totalMs, isRead(first)));
                inLoops.addAll(calls);
            }
        }
        // redundância: a MESMA leitura em qualquer ponto da execução (camadas diferentes inclusive)
        Map<String, List<Call>> sameRead = new LinkedHashMap<>();
        for (List<Call> calls : byGroup.values()) {
            for (Call c : calls) {
                if (c.key() != null && isRead(c) && !inLoops.contains(c)) {
                    sameRead.computeIfAbsent(c.component() + "|" + c.statementShape() + "|" + c.key(), k -> new ArrayList<>()).add(c);
                }
            }
        }
        for (List<Call> calls : sameRead.values()) {
            if (calls.size() >= 2) {
                long totalMs = calls.stream().mapToLong(c -> c.step().durationMs()).sum();
                out.add(redundant(ctx, flow, calls, 1, totalMs));
            }
        }
        return out;
    }

    private static boolean isRead(Call c) {
        return READS.contains(c.operation().toLowerCase(Locale.ROOT))
                || c.statementShape().toLowerCase(Locale.ROOT).startsWith("select");
    }

    private Insight nPlusOne(AnalysisContext ctx, FlowView flow, List<Call> calls, long totalMs, boolean read) {
        Call first = calls.get(0);
        FlowView.Step parent = first.step().parentId() != null ? flow.step(first.step().parentId()) : null;
        // a consulta "1": leitura em outro componente/forma sob o mesmo pai, antes do laço
        FlowView.Step driver = null;
        for (FlowView.Step s : flow.steps()) {
            if (s.parentId() != null && s.parentId().equals(first.step().parentId())
                    && (s.kind() == NodeKind.DYNAMODB || s.kind() == NodeKind.SQL)
                    && s.startMs() <= first.step().startMs() && s != first.step()) {
                driver = s;
                break;
            }
        }
        int queries = calls.size() + (driver != null ? 1 : 0);
        List<Evidence> ev = new ArrayList<>();
        ev.add(Evidence.span(calls.size() + "× " + first.operation() + " em " + Fmt.component(first.component()),
                Fmt.ms(totalMs) + " no total", ctx.executionId(), first.step().node().nodeId()));
        if (driver != null) {
            ev.add(Evidence.span("consulta que originou a lista", driver.node().label(), ctx.executionId(), driver.node().nodeId()));
        }
        if (parent != null) {
            ev.add(Evidence.span("laço dentro de", parent.node().label(), ctx.executionId(), parent.node().nodeId()));
        }
        ev.add(Evidence.metric("chaves distintas", String.valueOf(calls.stream().map(Call::key).distinct().count())));
        return InsightBuilder.of(N1_ID, name())
                .subject((parent != null ? parent.component() : "?") + "|" + first.component() + "|" + first.statementShape())
                .category(Insight.Category.DATA)
                .severity(calls.size() >= 50 ? Insight.Severity.HIGH : Insight.Severity.MEDIUM)
                .confidence(driver != null ? 0.93 : 0.82)
                .nature(Insight.Nature.CORRELATION)
                .title("Forte indício de N+1 em " + Fmt.component(first.component()))
                .observation("Foram " + queries + " consultas para recuperar " + calls.size() + " entidades"
                        + (parent != null ? " dentro de " + parent.node().label() : "") + ".")
                .evidence(ev)
                .correlation("Mesma operação, mesma tabela, chaves diferentes, mesmo pai — padrão de laço com acesso individual.")
                .hypothesis(read ? "O código percorre uma lista e busca cada item separadamente."
                        : "O código grava item a item quando poderia agrupar.")
                .recommend(read ? "Trocar o laço por BatchGetItem / consulta com IN / JOIN." : "Usar BatchWriteItem / escrita em lote.")
                .recommend("Avaliar cache local para chaves repetidas entre requisições.")
                .component(first.component())
                .component(parent != null ? parent.component() : null)
                .execution(ctx.executionId())
                .build();
    }

    private Insight redundant(AnalysisContext ctx, FlowView flow, List<Call> calls, int distinct, long totalMs) {
        Call first = calls.get(0);
        long wasted = totalMs - totalMs * distinct / calls.size();
        List<Evidence> ev = new ArrayList<>();
        for (Call c : calls.subList(0, Math.min(5, calls.size()))) {
            ev.add(Evidence.span(c.operation() + " " + (c.key() != null ? c.key() : ""), Fmt.ms(c.step().durationMs()),
                    ctx.executionId(), c.step().node().nodeId()));
        }
        return InsightBuilder.of(REDUNDANT_ID, name())
                .subject(first.component() + "|" + first.statementShape() + "|" + first.key())
                .category(Insight.Category.DATA)
                .severity(calls.size() >= 5 || wasted >= 200 ? Insight.Severity.MEDIUM : Insight.Severity.LOW)
                .confidence(0.999)
                .nature(Insight.Nature.FACT)
                .title("Consulta repetida na mesma requisição")
                .observation("O mesmo dado de " + Fmt.component(first.component()) + " foi lido " + calls.size()
                        + " vezes na mesma execução (" + distinct + " leitura(s) distinta(s)).")
                .evidence(ev)
                .correlation("Leituras idênticas — " + Fmt.ms(wasted) + " gastos com dado já obtido.")
                .hypothesis("Camadas diferentes consultam o mesmo registro sem compartilhar o resultado.")
                .recommend("Reutilizar o resultado dentro da requisição (passar adiante ou cache de escopo de request).")
                .recommend("Consolidar as consultas no serviço que orquestra o fluxo.")
                .component(first.component())
                .execution(ctx.executionId())
                .build();
    }
}
