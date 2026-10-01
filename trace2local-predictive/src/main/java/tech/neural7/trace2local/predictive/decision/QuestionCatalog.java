package tech.neural7.trace2local.predictive.decision;

import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.LogFact;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.NodeFact;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.RuleFact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de MICRO-DECISÕES que a lib faz sobre cada execução (ADR-011).
 * Perguntas em inglês (melhor desempenho medido do Jev), chaves de opção em
 * PT-BR (o que a UI exibe). Cada família tem uma política de fusão:
 * <ul>
 *   <li><b>fato</b> — decidido por evidência estrutural (tipo do nó, status,
 *       delta); nenhum modelo é consultado (economia e honestidade);</li>
 *   <li><b>semântica</b> — o Jev decide se a confiança passar do limiar; abaixo
 *       dele, o modelo determinístico; ambos fracos ⇒ revisão humana;</li>
 *   <li><b>numérica</b> — média ponderada pela confiança (Jev + determinístico),
 *       base de RANKINGS e notas.</li>
 * </ul>
 */
public final class QuestionCatalog {

    public static final String EXEC_OUTCOME = "exec.outcome";
    public static final String EXEC_RISK = "exec.risk";
    public static final String EXEC_READINESS = "exec.readiness";
    public static final String EXEC_ASYNC = "exec.async";
    public static final String EXEC_DATA = "exec.data";
    public static final String NODE_ROLE = "node.role";
    public static final String NODE_CRITICALITY = "node.criticality";
    public static final String RULE_VERDICT = "rule.verdict";
    public static final String LOG_CLASS = "log.class";

    static final LinkedHashMap<String, String> OUTCOME = map(
            "sucesso", "The flow completed and produced its intended business effect with no failed step",
            "recusa-protegida", "A business guard deliberately refused part of the operation (duplicate, idempotency or conditional check) and data stayed consistent",
            "falha-de-negocio", "The flow failed because a business rule or input validation rejected the request",
            "falha-tecnica", "The flow failed for a technical reason: infrastructure, network, timeout, configuration or programming error",
            "incompleto", "The flow is incomplete: part of the trace or an asynchronous branch was not observed");

    static final List<String> RISK = List.of(
            "No operational risk observed",
            "Low risk: minor latency or warnings only",
            "Moderate risk: degraded, refused or partially failed behaviour",
            "High risk: failures that corrupt data or break the business outcome");

    static final List<String> READINESS = List.of(
            "Blocked: must not be approved",
            "Needs fixes before approval",
            "Approvable with caveats",
            "Ready for business approval");

    static final LinkedHashMap<String, String> ROLE = map(
            "entrada", "Entry point that receives the request or event (HTTP endpoint, Lambda invocation)",
            "regra-de-negocio", "Business rule, validation or guard that decides whether the operation may proceed",
            "orquestracao", "Application logic that coordinates or orchestrates the next steps",
            "persistencia", "Database read or write",
            "mensageria", "Publishes or sends a message or event to a queue or topic",
            "consumo-assincrono", "Asynchronous consumer that processes a message coming from a queue or topic",
            "integracao-externa", "Call to an external system or third-party API");

    static final List<String> CRITICALITY = List.of(
            "Low business impact if this step fails",
            "Medium business impact if this step fails",
            "High business impact: the business outcome depends on this step");

    public static final LinkedHashMap<String, String> VERDICT = map(
            "respeitada", "The execution evidence shows the rule or documented behaviour was satisfied",
            "violada", "The execution evidence shows the rule or documented behaviour was broken",
            "nao-exercitada", "This execution did not exercise the rule or documented behaviour",
            "inconclusiva", "The evidence is insufficient to decide");

    static final LinkedHashMap<String, String> LOG = map(
            "erro-de-negocio", "Error caused by a business rule, validation or refused operation",
            "erro-tecnico", "Technical error: exception, infrastructure, network or configuration problem",
            "evento-de-negocio", "Normal business event: something meaningful happened to an order, payment, customer or record",
            "diagnostico", "Technical diagnostic or debug information with no business meaning",
            "plataforma", "Runtime platform line (START, END, REPORT, INIT)");

    /** Rótulos PT-BR dos níveis numéricos (UI). */
    public static final List<String> RISK_PT = List.of("sem risco", "baixo", "moderado", "alto");
    public static final List<String> READINESS_PT = List.of("bloqueada", "requer ajustes", "apta com ressalvas", "apta");
    public static final List<String> CRITICALITY_PT = List.of("baixa", "média", "alta");

    /** Limites por execução (custo previsível). */
    public record Limits(int maxNodes, int maxRules, int maxLogs) {
        public static final Limits DEFAULT = new Limits(24, 16, 30);
    }

    private QuestionCatalog() {}

    /** Todas as perguntas da execução (fatos inclusos — a fusão decide quem responde). */
    public static List<Question> build(ExecutionFacts f, Limits limits) {
        List<Question> qs = new ArrayList<>();
        qs.add(Question.choice("exec_outcome", EXEC_OUTCOME, f.executionId(),
                "What best describes the outcome of this execution?", OUTCOME));
        qs.add(Question.score("exec_risk", EXEC_RISK, f.executionId(),
                "How much operational risk does this execution reveal?", RISK));
        qs.add(Question.score("exec_readiness", EXEC_READINESS, f.executionId(),
                "Considering the business rules and the observed behaviour, how ready is this flow for business approval (homologation)?",
                READINESS));
        qs.add(Question.noul("exec_async", EXEC_ASYNC, f.executionId(),
                "Every message published to a queue or topic in this execution was consumed by an observed consumer."));
        qs.add(Question.noul("exec_data", EXEC_DATA, f.executionId(),
                "This execution changed persisted data (created, updated or deleted records)."));

        List<NodeFact> nodes = new ArrayList<>(f.nodes());
        // os mais relevantes primeiro: falhas, mutações, mais lentos
        nodes.sort(Comparator.<NodeFact>comparingInt(n -> n.failed() ? 0 : 1)
                .thenComparingInt(n -> n.mutationKind() != null ? 0 : 1)
                .thenComparing(Comparator.comparingLong(NodeFact::totalMs).reversed()));
        for (NodeFact n : nodes.subList(0, Math.min(limits.maxNodes(), nodes.size()))) {
            String ref = "step " + n.order() + " ('" + n.label() + "'"
                    + (n.operation() != null && !n.operation().isBlank() ? ", " + n.operation() : "") + ")";
            qs.add(Question.choice("node_role_" + n.order(), NODE_ROLE, n.nodeId(),
                    "What is the architectural role of " + ref + "?", ROLE));
            qs.add(Question.score("node_crit_" + n.order(), NODE_CRITICALITY, n.nodeId(),
                    "How critical is " + ref + " for the business outcome?", CRITICALITY));
        }
        for (RuleFact r : f.rules().subList(0, Math.min(limits.maxRules(), f.rules().size()))) {
            qs.add(Question.choice("rule_" + r.id(), RULE_VERDICT, r.id(),
                    (r.explicitRule() ? "Business rule" : "Documented behaviour") + " '" + r.term() + "': \""
                            + r.text() + "\". Based only on the execution evidence, what is the verdict?",
                    VERDICT));
        }
        int logCount = 0;
        for (LogFact l : f.logs()) {
            if (logCount >= limits.maxLogs()) {
                break;
            }
            String msg = l.message() == null ? "" : l.message();
            qs.add(Question.choice("log_" + l.index(), LOG_CLASS, String.valueOf(l.index()),
                    "Classify this log line (level " + l.level() + "): \"" + (msg.length() > 240 ? msg.substring(0, 240) + "…" : msg) + "\"",
                    LOG));
            logCount++;
        }
        return qs;
    }

    /** A pergunta é decidida por FATO (nenhum modelo consultado)? Devolve a resposta-fato ou {@code null}. */
    public static Answer fact(Question q, ExecutionFacts f) {
        switch (q.family()) {
            case EXEC_DATA -> {
                boolean changed = f.nodes().stream().anyMatch(n -> n.mutationKind() != null && !"READ_ONLY".equals(n.mutationKind())
                        && n.status() != tech.neural7.trace2local.model.NodeStatus.ERROR);
                return factNoul(q, changed, changed ? "há delta de dados CREATE/UPDATE/DELETE observado" : "nenhuma escrita observada");
            }
            case EXEC_ASYNC -> {
                long producers = f.nodes().stream().filter(NodeFact::producer).count();
                int missing = f.producersWithoutConsumer();
                if (producers == 0) {
                    return factNoul(q, true, "nenhuma mensagem publicada nesta execução");
                }
                return factNoul(q, missing == 0, missing == 0
                        ? "todas as " + producers + " publicação(ões) têm consumidor na mesma árvore"
                        : missing + " publicação(ões) sem consumidor observado");
            }
            case EXEC_OUTCOME -> {
                if ("COMPLETED".equals(f.status()) && f.failedNodes().isEmpty() && f.producersWithoutConsumer() == 0) {
                    return factChoice(q, "sucesso", "status COMPLETED, nenhum passo com erro");
                }
                if ("PARTIAL".equals(f.status()) || "ORPHANED".equals(f.status())) {
                    return factChoice(q, "incompleto", "status " + f.status() + " — parte do trace não foi observada");
                }
                return null;
            }
            case NODE_ROLE -> {
                NodeFact n = f.node(q.subject());
                if (n == null) {
                    return null;
                }
                String role = structuralRole(n);
                return role == null ? null : factChoice(q, role, "tipo " + n.kind() + " define o papel");
            }
            case LOG_CLASS -> {
                int idx = Integer.parseInt(q.subject());
                LogFact l = idx >= 0 && idx < f.logs().size() ? f.logs().get(idx) : null;
                if (l != null && l.platform()) {
                    return factChoice(q, "plataforma", "linha START/END/REPORT da plataforma Lambda");
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Papel decidido pela estrutura (null = semântico: BUSINESS/UNKNOWN). */
    static String structuralRole(NodeFact n) {
        NodeKind k = n.kind();
        return switch (k) {
            case DYNAMODB, SQL -> "persistencia";
            case SQS, SNS -> n.producer() ? "mensageria" : "consumo-assincrono";
            case HTTP_CLIENT -> "integracao-externa";
            case HTTP_SERVER -> n.asyncConsumer() ? "consumo-assincrono" : "entrada";
            case LAMBDA -> n.asyncConsumer() ? "consumo-assincrono" : (n.depth() == 0 ? "entrada" : "orquestracao");
            case BUSINESS, UNKNOWN -> null;
        };
    }

    private static Answer factNoul(Question q, boolean yes, String why) {
        return new Answer(q.id(), Question.Type.NOUL, null, yes ? 1.0 : 0.0, 1.0, Map.of(), Answer.ENGINE_FACT, "fatos", why);
    }

    private static Answer factChoice(Question q, String choice, String why) {
        Map<String, Double> probs = new LinkedHashMap<>();
        q.criteria().keySet().forEach(k -> probs.put(k, k.equals(choice) ? 1.0 : 0.0));
        return new Answer(q.id(), Question.Type.CHOICE, choice, 1.0, 1.0, probs, Answer.ENGINE_FACT, "fatos", why);
    }

    static LinkedHashMap<String, String> map(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
