package tech.neural7.trace2local.predictive.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.predictive.decision.Answer;
import tech.neural7.trace2local.predictive.decision.DecisionEngine;
import tech.neural7.trace2local.predictive.decision.DeterministicJevModel;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts;
import tech.neural7.trace2local.predictive.decision.FusionPolicy;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.NodeFact;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.RuleFact;
import tech.neural7.trace2local.predictive.decision.Question;
import tech.neural7.trace2local.predictive.decision.QuestionCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ASSISTENTE de execução com DUAS VISÕES somando esforços (ADR-011):
 * <ul>
 *   <li><b>técnica</b> — papel arquitetural de cada passo, criticidade, ranking
 *       de hotspots, caminho crítico, anomalias;</li>
 *   <li><b>executiva</b> — desfecho de negócio, regras/comportamentos
 *       documentados × evidência (homologação), risco e prontidão, checklist.</li>
 * </ul>
 * Todas as micro-decisões passam pelo motor em cascata (fato → Jev → Jev
 * determinístico) com fusão por família; o resultado declara a procedência de
 * cada número. Também produz os CAPÍTULOS didáticos da linha do tempo.
 */
public final class ExecutionAssistant {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> ROLE_PT = Map.of(
            "entrada", "Entrada", "regra-de-negocio", "Regra de negócio", "orquestracao", "Orquestração",
            "persistencia", "Persistência", "mensageria", "Mensageria", "consumo-assincrono", "Consumo assíncrono",
            "integracao-externa", "Integração externa");
    private static final Map<String, String> OUTCOME_PT = Map.of(
            "sucesso", "Sucesso", "recusa-protegida", "Recusa protegida pela regra", "falha-de-negocio", "Falha de negócio",
            "falha-tecnica", "Falha técnica", "incompleto", "Incompleto");
    private static final Map<String, String> VERDICT_PT = Map.of(
            "respeitada", "Respeitada", "violada", "Violada", "nao-exercitada", "Não exercitada", "inconclusiva", "Inconclusiva");
    private static final Map<String, String> LOG_PT = Map.of(
            "erro-de-negocio", "erro de negócio", "erro-tecnico", "erro técnico", "evento-de-negocio", "evento de negócio",
            "diagnostico", "diagnóstico", "plataforma", "plataforma");

    private final DecisionEngine engine;
    private final QuestionCatalog.Limits limits;

    public ExecutionAssistant(DecisionEngine engine) {
        this(engine, QuestionCatalog.Limits.DEFAULT);
    }

    public ExecutionAssistant(DecisionEngine engine, QuestionCatalog.Limits limits) {
        this.engine = engine;
        this.limits = limits;
    }

    /** Uma decisão fundida: final + alternativa (o outro motor) + pedido de revisão humana. */
    record Decision(Answer answer, Answer alternative, boolean needsReview) {}

    public ObjectNode analyze(Execution execution, List<LogEntry> logs, Map<String, String> glossary) {
        long t0 = System.nanoTime();
        ExecutionFacts facts = ExecutionFacts.of(execution, logs, glossary);
        List<Question> questions = QuestionCatalog.build(facts, limits);
        DeterministicJevModel deterministic = new DeterministicJevModel(facts);

        // 1) fatos: respondidos sem modelo
        Map<String, Answer> factAnswers = new LinkedHashMap<>();
        List<Question> semantic = new ArrayList<>();
        for (Question q : questions) {
            Answer f = QuestionCatalog.fact(q, facts);
            if (f != null) {
                factAnswers.put(q.id(), f);
            } else {
                semantic.add(q);
            }
        }
        // 2) semânticas: cascata (cassete → Jev → determinístico)
        Map<String, String> state = StateBuilder.of(facts, engine != null ? engine.config().egress() : null);
        Map<String, Answer> modelAnswers = engine != null ? engine.ask(state, semantic, deterministic)
                : safeDecide(deterministic, state, semantic);
        // 3) a opinião determinística SEMPRE existe (para fusão e transparência)
        Map<String, Answer> detAnswers = safeDecide(deterministic, state, semantic);
        double threshold = engine != null ? engine.config().acceptThreshold() : 0.8;

        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (Question q : questions) {
            Answer fact = factAnswers.get(q.id());
            if (fact != null) {
                decisions.put(q.id(), new Decision(fact, null, false));
                continue;
            }
            decisions.put(q.id(), fuse(q, modelAnswers.get(q.id()), detAnswers.get(q.id()), threshold));
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("executionId", facts.executionId());
        root.put("generatedAt", java.time.Instant.now().toString());
        ObjectNode executive = root.putObject("executive");
        ObjectNode technical = root.putObject("technical");
        writeExecutive(executive, facts, questions, decisions);
        writeTechnical(technical, facts, questions, decisions);
        writeChapters(root.putArray("chapters"), facts, questions, decisions);
        writeLogs(root.putObject("logs"), questions, decisions);
        ObjectNode stats = root.putObject("stats");
        stats.put("questions", questions.size());
        stats.put("facts", factAnswers.size());
        Map<String, Integer> byEngine = new LinkedHashMap<>();
        int review = 0;
        for (Decision d : decisions.values()) {
            byEngine.merge(d.answer().engine(), 1, Integer::sum);
            if (d.needsReview()) {
                review++;
            }
        }
        ObjectNode be = stats.putObject("byEngine");
        byEngine.forEach(be::put);
        stats.put("needsReview", review);
        stats.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
        if (engine != null) {
            stats.set("engine", MAPPER.valueToTree(engine.status()));
        }
        return root;
    }

    /**
     * Fusão por família: NUMÉRICAS (score) = média ponderada pela confiança dos
     * dois motores; SEMÂNTICAS = Jev se ≥ limiar, senão determinístico; ambos
     * fracos ⇒ revisão humana.
     */
    static Decision fuse(Question q, Answer model, Answer det, double threshold) {
        if (model == null) {
            return new Decision(det, null, det == null || det.decisiveness() < 0.35);
        }
        boolean fromModel = Answer.ENGINE_JEV.equals(model.engine()) || Answer.ENGINE_REPLAY.equals(model.engine());
        if (!fromModel || det == null) {
            return new Decision(model, null, model.decisiveness() < 0.35);
        }
        if (q.type() == Question.Type.SCORE) {
            // numérica: média ponderada pela confiança; o Jev tem meia voz até ser medido nesta família
            double wm = Math.max(0.05, model.confidence()) * FusionPolicy.JEV_SCORE_WEIGHT;
            double wd = Math.max(0.05, det.confidence());
            double v = (model.value() * wm + det.value() * wd) / (wm + wd);
            int idx = (int) Math.max(0, Math.min(q.levels().size() - 1, Math.round(v)));
            Answer fused = new Answer(q.id(), Question.Type.SCORE, q.levels().get(idx), Math.round(v * 1000) / 1000.0,
                    Math.round(Math.max(wm, wd) * 1000) / 1000.0, model.probabilities(), "fusion", model.model(),
                    "Jev " + fmt(model.value()) + " (conf. " + pct(model.confidence()) + ", peso ½) + determinístico "
                            + fmt(det.value()) + " (conf. " + pct(det.confidence()) + ") — " + det.rationale());
            return new Decision(fused, det, Math.abs(model.value() - det.value()) >= 1.5);
        }
        boolean agree = q.type() == Question.Type.NOUL ? model.yes() == det.yes()
                : java.util.Objects.equals(model.choice(), det.choice());
        FusionPolicy.Policy policy = FusionPolicy.of(q);
        Answer chosen = FusionPolicy.choose(q, model, det);
        if (chosen == model) {
            return new Decision(model.withRationale(agree ? "Jev e motor determinístico concordam — " + det.rationale()
                    : "Jev decidiu com " + pct(model.decisiveness()) + " (política " + policy.trust() + "); o determinístico diria "
                    + label(det)), det, false);
        }
        String why = det.rationale() + (agree ? " · Jev concorda (" + pct(model.decisiveness()) + ")"
                : " · Jev divergiu (" + label(model) + ", " + pct(model.decisiveness()) + ") — política "
                + policy.trust() + " manteve a regra");
        // divergência forte entre motores com regra fraca ⇒ revisão humana
        boolean review = det.decisiveness() < 0.35 || (!agree && model.decisiveness() >= 0.9 && det.decisiveness() < 0.6);
        return new Decision(det.withRationale(why), model, review);
    }

    private static String label(Answer a) {
        return a.type() == Question.Type.NOUL ? (a.yes() ? "sim" : "não") : String.valueOf(a.choice());
    }

    // ================================================================== executiva

    private void writeExecutive(ObjectNode out, ExecutionFacts f, List<Question> qs, Map<String, Decision> d) {
        Decision outcome = d.get("exec_outcome");
        Decision readiness = d.get("exec_readiness");
        Decision risk = d.get("exec_risk");
        out.set("outcome", decisionJson(outcome, OUTCOME_PT));
        out.set("readiness", scoreJson(readiness, QuestionCatalog.READINESS_PT));
        out.set("risk", scoreJson(risk, QuestionCatalog.RISK_PT));
        out.set("dataChanged", noulJson(d.get("exec_data")));
        out.set("asyncComplete", noulJson(d.get("exec_async")));
        ArrayNode rules = out.putArray("rules");
        int respected = 0;
        int violated = 0;
        int notExercised = 0;
        int inconclusive = 0;
        for (RuleFact r : f.rules()) {
            Decision v = d.get("rule_" + r.id());
            if (v == null) {
                continue;
            }
            ObjectNode n = decisionJson(v, VERDICT_PT);
            n.put("id", r.id());
            n.put("term", r.term());
            n.put("text", r.text());
            n.put("explicit", r.explicitRule());
            ArrayNode ids = n.putArray("nodeIds");
            r.nodeIds().forEach(ids::add);
            rules.add(n);
            switch (String.valueOf(v.answer().choice())) {
                case "respeitada" -> respected++;
                case "violada" -> violated++;
                case "nao-exercitada" -> notExercised++;
                default -> inconclusive++;
            }
        }
        ObjectNode tally = out.putObject("rulesTally");
        tally.put("respeitada", respected);
        tally.put("violada", violated);
        tally.put("nao-exercitada", notExercised);
        tally.put("inconclusiva", inconclusive);
        // checklist de homologação (determinístico, auditável)
        ArrayNode checklist = out.putArray("checklist");
        check(checklist, "Fluxo concluiu", !"FAILED".equals(f.status()), "status " + f.status());
        check(checklist, "Nenhum passo com erro inesperado", f.failedNodes().stream().allMatch(n ->
                String.valueOf(n.errorType()).contains("ConditionalCheckFailed")), f.failedNodes().size() + " passo(s) vermelho(s)");
        check(checklist, "Efeitos assíncronos consumidos", f.producersWithoutConsumer() == 0,
                f.producersWithoutConsumer() == 0 ? "ok" : f.producersWithoutConsumer() + " publicação(ões) sem consumidor");
        check(checklist, "Nenhuma regra violada", violated == 0, violated + " violada(s)");
        check(checklist, "Regras do glossário exercitadas", f.rules().isEmpty() || notExercised == 0,
                f.rules().isEmpty() ? "sem glossário (trace2local-business.md)" : notExercised + " não exercitada(s)");
        check(checklist, "Telemetria completa (sem avisos de honestidade)", f.warnings() == 0, f.warnings() + " aviso(s)");
        out.put("headline", headline(f, outcome, readiness, violated));
        out.put("summary", summary(f, outcome, risk, respected, violated, notExercised, inconclusive));
    }

    private static void check(ArrayNode arr, String item, boolean ok, String detail) {
        ObjectNode n = arr.addObject();
        n.put("item", item);
        n.put("ok", ok);
        n.put("detail", detail);
    }

    private static String headline(ExecutionFacts f, Decision outcome, Decision readiness, int violated) {
        String o = OUTCOME_PT.getOrDefault(String.valueOf(outcome.answer().choice()), "Resultado");
        String r = readiness.answer().choice() != null ? QuestionCatalog.READINESS_PT.get(
                Math.max(0, Math.min(3, (int) Math.round(readiness.answer().value())))) : "?";
        String root = f.nodes().isEmpty() ? "execução" : f.nodes().get(0).label();
        return o + " em " + root + " — homologação: " + r + (violated > 0 ? " (" + violated + " regra(s) violada(s))" : "");
    }

    private static String summary(ExecutionFacts f, Decision outcome, Decision risk, int ok, int bad, int notEx, int inc) {
        StringBuilder sb = new StringBuilder();
        sb.append("A execução ").append(f.trigger().equals("UI_DISPATCH") ? "disparada pela UI" : "observada")
                .append(" percorreu ").append(f.nodes().size()).append(" passo(s) em ").append(f.durationMs()).append(" ms");
        long writes = f.nodes().stream().filter(n -> n.mutationKind() != null && !"READ_ONLY".equals(n.mutationKind()) && !n.failed()).count();
        if (writes > 0) {
            sb.append(", alterando dados em ").append(writes).append(" ponto(s)");
        }
        sb.append(". Desfecho: ").append(OUTCOME_PT.getOrDefault(String.valueOf(outcome.answer().choice()), "?").toLowerCase(Locale.ROOT));
        sb.append("; risco ").append(QuestionCatalog.RISK_PT.get((int) Math.max(0, Math.min(3, Math.round(risk.answer().value())))));
        if (ok + bad + notEx + inc > 0) {
            sb.append(". Regras: ").append(ok).append(" respeitada(s), ").append(bad).append(" violada(s), ")
                    .append(notEx).append(" não exercitada(s), ").append(inc).append(" inconclusiva(s)");
        }
        return sb.append('.').toString();
    }

    // ================================================================== técnica

    private void writeTechnical(ObjectNode out, ExecutionFacts f, List<Question> qs, Map<String, Decision> d) {
        ObjectNode roles = out.putObject("roles");
        ObjectNode crit = out.putObject("criticality");
        Map<String, Double> critValue = new LinkedHashMap<>();
        for (Question q : qs) {
            Decision dec = d.get(q.id());
            if (QuestionCatalog.NODE_ROLE.equals(q.family())) {
                roles.set(q.subject(), decisionJson(dec, ROLE_PT));
            } else if (QuestionCatalog.NODE_CRITICALITY.equals(q.family())) {
                crit.set(q.subject(), scoreJson(dec, QuestionCatalog.CRITICALITY_PT));
                critValue.put(q.subject(), dec.answer().value());
            }
        }
        // ranking numérico de hotspots: latência própria + criticidade + erro + escrita
        long total = Math.max(1, f.durationMs());
        long maxSelf = Math.max(1, f.nodes().stream().mapToLong(NodeFact::selfMs).max().orElse(1));
        List<Map.Entry<NodeFact, Double>> ranked = new ArrayList<>();
        for (NodeFact n : f.nodes()) {
            double latency = (double) n.selfMs() / maxSelf;
            double c = critValue.getOrDefault(n.nodeId(), 0.5) / 2.0;
            double score = 0.45 * latency + 0.35 * c + (n.failed() ? 0.2 : 0)
                    + (n.mutationKind() != null && !"READ_ONLY".equals(n.mutationKind()) ? 0.05 : 0);
            ranked.add(Map.entry(n, Math.round(score * 1000) / 1000.0));
        }
        ranked.sort(Map.Entry.<NodeFact, Double>comparingByValue().reversed());
        ArrayNode hot = out.putArray("hotspots");
        int rank = 1;
        for (var e : ranked.subList(0, Math.min(8, ranked.size()))) {
            NodeFact n = e.getKey();
            ObjectNode h = hot.addObject();
            h.put("rank", rank++);
            h.put("nodeId", n.nodeId());
            h.put("label", n.label());
            h.put("kind", n.kind().name());
            h.put("totalMs", n.totalMs());
            h.put("selfMs", n.selfMs());
            h.put("share", Math.round(1000.0 * n.selfMs() / total) / 1000.0);
            h.put("criticality", critValue.getOrDefault(n.nodeId(), 0.5));
            h.put("score", e.getValue());
            h.put("failed", n.failed());
        }
        // caminho crítico: cadeia que termina por último
        ArrayNode cp = out.putArray("criticalPath");
        NodeFact cur = f.nodes().isEmpty() ? null : f.nodes().get(0);
        int guard = 0;
        while (cur != null && guard++ < 128) {
            cp.add(cur.nodeId());
            NodeFact next = null;
            for (NodeFact c : f.nodes()) {
                if (cur.nodeId().equals(c.parentId()) && (next == null || c.startOffsetMs() + c.totalMs() > next.startOffsetMs() + next.totalMs())) {
                    next = c;
                }
            }
            cur = next;
        }
        ArrayNode anomalies = out.putArray("anomalies");
        for (NodeFact n : f.failedNodes()) {
            ObjectNode a = anomalies.addObject();
            boolean guardRefusal = String.valueOf(n.errorType()).contains("ConditionalCheckFailed");
            a.put("severity", guardRefusal ? "info" : "alta");
            a.put("title", (guardRefusal ? "Recusa condicional em " : "Erro em ") + n.label());
            a.put("detail", n.errorType() + (n.errorMessage() != null ? ": " + n.errorMessage() : ""));
            a.put("nodeId", n.nodeId());
        }
        if (f.producersWithoutConsumer() > 0) {
            ObjectNode a = anomalies.addObject();
            a.put("severity", "média");
            a.put("title", "Publicação sem consumidor observado");
            a.put("detail", f.producersWithoutConsumer() + " ramo(s) assíncrono(s) sem continuação na árvore");
        }
    }

    // ================================================================== capítulos

    private void writeChapters(ArrayNode out, ExecutionFacts f, List<Question> qs, Map<String, Decision> d) {
        Map<String, String> roleOf = new LinkedHashMap<>();
        for (Question q : qs) {
            if (QuestionCatalog.NODE_ROLE.equals(q.family())) {
                roleOf.put(q.subject(), String.valueOf(d.get(q.id()).answer().choice()));
            }
        }
        List<NodeFact> ordered = new ArrayList<>(f.nodes());
        ordered.sort(Comparator.comparingLong(NodeFact::startOffsetMs).thenComparingInt(NodeFact::order));
        String currentRole = null;
        ObjectNode chapter = null;
        int index = 1;
        for (NodeFact n : ordered) {
            String role = roleOf.getOrDefault(n.nodeId(), structural(n));
            String chapterKind = chapterOf(role);
            if (chapter == null || !chapterKind.equals(currentRole)) {
                chapter = out.addObject();
                chapter.put("index", index++);
                chapter.put("kind", chapterKind);
                chapter.put("title", ROLE_PT.getOrDefault(chapterKind, chapterKind));
                chapter.put("fromMs", n.startOffsetMs());
                chapter.put("toMs", n.startOffsetMs() + n.totalMs());
                chapter.putArray("nodeIds");
                chapter.put("summary", "");
                currentRole = chapterKind;
            }
            ((ArrayNode) chapter.get("nodeIds")).add(n.nodeId());
            chapter.put("toMs", Math.max(chapter.get("toMs").asLong(), n.startOffsetMs() + n.totalMs()));
            String piece = n.label() + (n.mutationKind() != null && !"READ_ONLY".equals(n.mutationKind())
                    ? " (" + n.mutationKind().toLowerCase(Locale.ROOT) + (n.mutationKey() != null ? " " + n.mutationKey() : "") + ")" : "")
                    + (n.failed() ? " ✕" : "");
            String prev = chapter.get("summary").asText();
            if (prev.length() < 220) {
                chapter.put("summary", prev.isEmpty() ? piece : prev + " · " + piece);
            }
        }
        ObjectNode end = out.addObject();
        end.put("index", index);
        end.put("kind", "desfecho");
        end.put("title", "Desfecho");
        end.put("fromMs", f.durationMs());
        end.put("toMs", f.durationMs());
        end.putArray("nodeIds");
        end.put("summary", OUTCOME_PT.getOrDefault(String.valueOf(d.get("exec_outcome").answer().choice()), f.status())
                + " em " + f.durationMs() + " ms");
    }

    private static String chapterOf(String role) {
        return switch (role == null ? "" : role) {
            case "regra-de-negocio", "orquestracao" -> "regra-de-negocio";
            case "persistencia", "mensageria", "consumo-assincrono", "integracao-externa", "entrada" -> role;
            default -> "orquestracao";
        };
    }

    private static String structural(NodeFact n) {
        return switch (n.kind()) {
            case DYNAMODB, SQL -> "persistencia";
            case SQS, SNS -> "mensageria";
            case HTTP_CLIENT -> "integracao-externa";
            default -> n.depth() == 0 ? "entrada" : "orquestracao";
        };
    }

    private void writeLogs(ObjectNode out, List<Question> qs, Map<String, Decision> d) {
        for (Question q : qs) {
            if (QuestionCatalog.LOG_CLASS.equals(q.family())) {
                out.set(q.subject(), decisionJson(d.get(q.id()), LOG_PT));
            }
        }
    }

    // ================================================================== json

    private static ObjectNode decisionJson(Decision d, Map<String, String> labels) {
        ObjectNode n = MAPPER.createObjectNode();
        Answer a = d.answer();
        n.put("choice", a.choice());
        n.put("label", labels.getOrDefault(String.valueOf(a.choice()), String.valueOf(a.choice())));
        n.put("confidence", Math.round(a.decisiveness() * 1000) / 1000.0);
        n.put("engine", a.engine());
        n.put("model", a.model());
        n.put("rationale", a.rationale());
        n.put("needsReview", d.needsReview());
        if (!a.probabilities().isEmpty()) {
            n.set("probabilities", MAPPER.valueToTree(a.probabilities()));
        }
        if (d.alternative() != null) {
            ObjectNode alt = n.putObject("alternative");
            alt.put("engine", d.alternative().engine());
            alt.put("choice", d.alternative().choice());
            alt.put("label", labels.getOrDefault(String.valueOf(d.alternative().choice()), String.valueOf(d.alternative().choice())));
            alt.put("confidence", Math.round(d.alternative().decisiveness() * 1000) / 1000.0);
        }
        return n;
    }

    private static ObjectNode scoreJson(Decision d, List<String> labels) {
        ObjectNode n = decisionJson(d, Map.of());
        double v = d.answer().value();
        int idx = (int) Math.max(0, Math.min(labels.size() - 1, Math.round(v)));
        n.put("value", Math.round(v * 100) / 100.0);
        n.put("max", labels.size() - 1);
        n.put("level", idx);
        n.put("label", labels.get(idx));
        if (d.alternative() != null) {
            ((ObjectNode) n.get("alternative")).put("value", Math.round(d.alternative().value() * 100) / 100.0);
        }
        return n;
    }

    private static ObjectNode noulJson(Decision d) {
        ObjectNode n = decisionJson(d, Map.of());
        n.put("value", Math.round(d.answer().value() * 1000) / 1000.0);
        n.put("yes", d.answer().yes());
        n.put("label", d.answer().yes() ? "sim" : "não");
        return n;
    }

    private static Map<String, Answer> safeDecide(DeterministicJevModel m, Map<String, String> state, List<Question> qs) {
        return m.decide(state, qs);
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
