package tech.neural7.trace2local.predictive.decision;

import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.LogFact;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.NodeFact;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts.RuleFact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Jev determinístico" (ADR-011): as MESMAS primitivas do Jev — noul, choice,
 * score — respondidas 100% em processo, sem rede, sem chave e de forma
 * REPRODUTÍVEL (mesma entrada ⇒ mesma saída, bit a bit). É o caminho quando a
 * chave não roda (ausente, recusada, sem crédito, proxy bloqueando, CI offline).
 *
 * <p>Dois níveis:
 * <ol>
 *   <li><b>skills por família</b> (com {@link ExecutionFacts}): regras explícitas e
 *       auditáveis — cada resposta vem com {@link Answer#rationale()};</li>
 *   <li><b>motor léxico genérico</b> (qualquer pergunta, só com o estado):
 *       cobertura de tokens PT/EN + softmax com temperatura fixa — fraco, porém
 *       honesto: a confiança baixa faz a fusão pedir revisão humana.</li>
 * </ol>
 */
public final class DeterministicJevModel implements DecisionModel {

    public static final String VERSION = "t2l-deterministic-1.0";

    private final ExecutionFacts facts;

    public DeterministicJevModel() {
        this(null);
    }

    public DeterministicJevModel(ExecutionFacts facts) {
        this.facts = facts;
    }

    @Override
    public String name() {
        return Answer.ENGINE_DETERMINISTIC;
    }

    @Override
    public Map<String, Answer> decide(Map<String, String> state, List<Question> questions) {
        Map<String, Answer> out = new LinkedHashMap<>();
        String stateText = String.join("\n", state.values());
        for (Question q : questions) {
            Answer a = facts != null ? skill(q) : null;
            if (a == null) {
                a = lexical(q, stateText);
            }
            out.put(q.id(), a);
        }
        return out;
    }

    // ================================================================== skills

    private Answer skill(Question q) {
        if (q.family() == null) {
            return null;
        }
        return switch (q.family()) {
            case QuestionCatalog.EXEC_OUTCOME -> outcome(q);
            case QuestionCatalog.EXEC_RISK -> risk(q);
            case QuestionCatalog.EXEC_READINESS -> readiness(q);
            case QuestionCatalog.EXEC_ASYNC, QuestionCatalog.EXEC_DATA -> {
                Answer fact = QuestionCatalog.fact(q, facts);
                yield fact == null ? null : fact.withEngine(Answer.ENGINE_DETERMINISTIC);
            }
            case QuestionCatalog.NODE_ROLE -> role(q);
            case QuestionCatalog.NODE_CRITICALITY -> criticality(q);
            case QuestionCatalog.RULE_VERDICT -> verdict(q);
            case QuestionCatalog.LOG_CLASS -> logClass(q);
            default -> null;
        };
    }

    private Answer outcome(Question q) {
        Answer fact = QuestionCatalog.fact(q, facts);
        if (fact != null) {
            return fact.withEngine(Answer.ENGINE_DETERMINISTIC);
        }
        List<NodeFact> failed = facts.failedNodes();
        String errors = String.join(" ", failed.stream().map(n -> n.errorType() + " " + n.errorMessage()).toList());
        boolean guard = TextFeatures.containsAny(errors, TextFeatures.BUSINESS_GUARD);
        int business = TextFeatures.countAny(errors, TextFeatures.BUSINESS_FAILURE);
        int technical = TextFeatures.countAny(errors, TextFeatures.TECHNICAL_FAILURE);
        Map<String, Double> score = new LinkedHashMap<>();
        q.criteria().keySet().forEach(k -> score.put(k, 0.0));
        String why;
        if ("COMPLETED".equals(facts.status()) && guard) {
            score.put("recusa-protegida", 3.0);
            why = "execução concluída com recusa de guarda (" + firstType(failed) + ") — invariante preservado";
        } else if ("COMPLETED".equals(facts.status()) && facts.producersWithoutConsumer() > 0) {
            score.put("incompleto", 2.5);
            score.put("sucesso", 1.5);
            why = "concluída, mas há publicação sem consumidor observado";
        } else if ("COMPLETED".equals(facts.status())) {
            score.put("sucesso", 2.0);
            score.put("recusa-protegida", 1.0);
            why = "concluída com passo vermelho tratado (" + firstType(failed) + ")";
        } else if (technical > business) {
            score.put("falha-tecnica", 2.0 + technical * 0.5);
            score.put("falha-de-negocio", 1.0 + business * 0.5);
            why = "erro com assinatura técnica (" + firstType(failed) + ")";
        } else if (guard) {
            score.put("recusa-protegida", 2.5);
            score.put("falha-de-negocio", 2.0);
            why = "falha por guarda condicional (" + firstType(failed) + ")";
        } else {
            score.put("falha-de-negocio", 2.0 + business * 0.5);
            score.put("falha-tecnica", 1.0 + technical * 0.5);
            why = business > 0 ? "erro com vocabulário de regra de negócio (" + firstType(failed) + ")"
                    : "falha sem assinatura técnica clara — classificada como negócio por padrão";
        }
        return choice(q, score, 0.6, why);
    }

    private Answer risk(Question q) {
        double v = 0;
        List<String> why = new ArrayList<>();
        int failed = facts.failedNodes().size();
        boolean guardOnly = failed > 0 && facts.failedNodes().stream().allMatch(n ->
                TextFeatures.containsAny(n.errorType() + " " + n.errorMessage(), TextFeatures.BUSINESS_GUARD));
        if (failed > 0) {
            // recusa pela guarda (ConditionalCheckFailed na escrita condicional) é o sistema
            // FUNCIONANDO: risco baixo — achado no trace real da reentrega idempotente
            v += guardOnly ? 0.8 : 2.2;
            why.add(failed + " passo(s) com erro" + (guardOnly ? " (recusa da guarda de negócio — esperada)" : ""));
        }
        if ("FAILED".equals(facts.status()) && !guardOnly) {
            v += 0.6;
            why.add("execução FAILED");
        }
        if (facts.producersWithoutConsumer() > 0) {
            v += 0.8;
            why.add("mensagem sem consumidor observado");
        }
        if (facts.warnings() > 0) {
            v += 0.5;
            why.add(facts.warnings() + " aviso(s) de honestidade");
        }
        if (facts.durationMs() > 3000) {
            v += 0.6;
            why.add("duração alta (" + facts.durationMs() + " ms)");
        } else if (facts.durationMs() > 1000) {
            v += 0.3;
            why.add("duração acima de 1 s");
        }
        boolean writesUnderFailure = "FAILED".equals(facts.status()) && facts.nodes().stream()
                .anyMatch(n -> n.mutationKind() != null && !"READ_ONLY".equals(n.mutationKind()) && !n.failed());
        if (writesUnderFailure) {
            v += 0.7;
            why.add("escrita persistida antes da falha (efeito parcial)");
        }
        v = Math.min(3, v);
        return score(q, v, why.isEmpty() ? "nenhum sinal de risco" : String.join("; ", why), 0.75);
    }

    private Answer readiness(Question q) {
        double v = 3;
        List<String> why = new ArrayList<>();
        boolean guardOk = facts.failedNodes().stream().allMatch(n ->
                TextFeatures.containsAny(n.errorType() + " " + n.errorMessage(), TextFeatures.BUSINESS_GUARD));
        if ("FAILED".equals(facts.status()) && !(guardOk && !facts.failedNodes().isEmpty())) {
            v -= 2;
            why.add("execução falhou");
        } else if ("FAILED".equals(facts.status())) {
            why.add("recusa protegida pela guarda (comportamento esperado da regra)");
        }
        if (!facts.failedNodes().isEmpty() && !guardOk) {
            v -= 1;
            why.add("passo com erro não-guarda");
        }
        if (facts.producersWithoutConsumer() > 0) {
            v -= 0.8;
            why.add("ramo assíncrono não observado");
        }
        if (facts.warnings() > 0) {
            v -= 0.5;
            why.add("avisos de honestidade");
        }
        long violated = facts.rules().stream().filter(r -> "violada".equals(ruleVerdict(r)[0])).count();
        if (violated > 0) {
            v -= 1.5;
            why.add(violated + " regra(s) violada(s)");
        }
        if (facts.rules().isEmpty()) {
            v -= 0.4;
            why.add("sem glossário de regras: homologação por comportamento técnico apenas");
        }
        v = Math.max(0, Math.min(3, v));
        return score(q, v, why.isEmpty() ? "fluxo íntegro e regras atendidas" : String.join("; ", why), 0.7);
    }

    private Answer role(Question q) {
        NodeFact n = facts.node(q.subject());
        if (n == null) {
            return null;
        }
        String structural = QuestionCatalog.structuralRole(n);
        Map<String, Double> score = new LinkedHashMap<>();
        q.criteria().keySet().forEach(k -> score.put(k, 0.0));
        if (structural != null) {
            score.put(structural, 4.0);
            return choice(q, score, 0.5, "tipo " + n.kind() + " ⇒ " + structural);
        }
        String l = TextFeatures.fold(n.label() + " " + n.operation());
        if (l.contains("guard") || l.contains("valid") || l.contains("check") || l.contains("regra")
                || l.contains("rule") || l.contains("idempot") || l.contains("confirm") || l.contains("autoriz")
                || l.contains("verific") || l.contains("polic")) {
            score.put("regra-de-negocio", 3.0);
            score.put("orquestracao", 1.0);
            return choice(q, score, 0.7, "nome indica guarda/validação/regra");
        }
        if (l.contains("notif") || l.contains("publish") || l.contains("send") || l.contains("enviar") || l.contains("avis")) {
            score.put("mensageria", 2.0);
            score.put("integracao-externa", 1.6);
            score.put("orquestracao", 1.0);
            return choice(q, score, 0.8, "nome indica envio/notificação");
        }
        score.put("orquestracao", 2.0);
        score.put("regra-de-negocio", 1.4);
        return choice(q, score, 0.8, "passo de negócio que coordena os filhos");
    }

    private Answer criticality(Question q) {
        NodeFact n = facts.node(q.subject());
        if (n == null) {
            return null;
        }
        double v = 0.6;
        List<String> why = new ArrayList<>();
        if (n.mutationKind() != null && !"READ_ONLY".equals(n.mutationKind())) {
            v += 0.9;
            why.add("altera dados (" + n.mutationKind() + ")");
        }
        if (n.failed()) {
            v += 0.6;
            why.add("passo com erro");
        }
        if (n.kind() == NodeKind.BUSINESS) {
            v += 0.4;
            why.add("lógica de negócio");
        }
        if (n.depth() == 0) {
            v += 0.3;
            why.add("raiz da jornada");
        }
        if (n.producer()) {
            v += 0.3;
            why.add("dispara efeito assíncrono");
        }
        if (n.kind() == NodeKind.DYNAMODB && "READ_ONLY".equals(n.mutationKind())) {
            v -= 0.3;
        }
        v = Math.max(0, Math.min(2, v));
        return score(q, v, why.isEmpty() ? "passo de suporte" : String.join("; ", why), 0.65);
    }

    private Answer verdict(Question q) {
        RuleFact r = facts.rules().stream().filter(x -> x.id().equals(q.subject())).findFirst().orElse(null);
        if (r == null) {
            return null;
        }
        String[] v = ruleVerdict(r);
        Map<String, Double> score = new LinkedHashMap<>();
        q.criteria().keySet().forEach(k -> score.put(k, 0.0));
        double strength = Double.parseDouble(v[2]);
        score.put(v[0], strength);
        if (!"inconclusiva".equals(v[0])) {
            score.put("inconclusiva", 1.0);
        }
        return choice(q, score, 0.6, v[1]);
    }

    /** [veredito, justificativa, força] — heurística auditável regra × evidência. */
    String[] ruleVerdict(RuleFact r) {
        if (r.nodeIds().isEmpty()) {
            return new String[] {"nao-exercitada", "nenhum passo desta execução casa o termo '" + r.term() + "'", "3.0"};
        }
        List<NodeFact> matched = r.nodeIds().stream().map(facts::node).filter(java.util.Objects::nonNull).toList();
        String ruleText = TextFeatures.fold(r.text());
        boolean isGuardRule = TextFeatures.containsAny(ruleText, TextFeatures.GUARD_RULE_WORDS)
                || ruleText.contains(" so ") || ruleText.startsWith("so ") || ruleText.contains("apenas")
                || ruleText.contains("somente");
        // passos do subárvore do termo (o guarda costuma estar abaixo do nó nomeado)
        List<NodeFact> scope = new ArrayList<>(matched);
        for (NodeFact m : matched) {
            for (NodeFact n : facts.nodes()) {
                if (isDescendant(n, m) && !scope.contains(n)) {
                    scope.add(n);
                }
            }
        }
        List<NodeFact> failed = scope.stream().filter(NodeFact::failed).toList();
        String failedText = String.join(" ", failed.stream().map(n -> n.errorType() + " " + n.errorMessage()).toList());
        if (!failed.isEmpty() && isGuardRule && TextFeatures.containsAny(failedText, TextFeatures.BUSINESS_GUARD)) {
            return new String[] {"respeitada", "a guarda recusou a operação (" + firstType(failed)
                    + ") — exatamente o que a regra exige", "3.0"};
        }
        if (!failed.isEmpty()) {
            boolean technical = TextFeatures.countAny(failedText, TextFeatures.TECHNICAL_FAILURE)
                    > TextFeatures.countAny(failedText, TextFeatures.BUSINESS_FAILURE);
            return technical
                    ? new String[] {"inconclusiva", "falha técnica (" + firstType(failed) + ") impediu observar a regra", "2.0"}
                    : new String[] {"violada", "o passo da regra falhou (" + firstType(failed) + ")", "2.2"};
        }
        // estados citados na regra (PENDING, CONFIRMED, BILLED…) × transições observadas nos deltas
        Set<String> ruleStates = stateTokens(r.text());
        List<String> transitions = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        List<String> missedTarget = new ArrayList<>();
        for (NodeFact n : scope) {
            n.deltaValues().forEach((field, ba) -> {
                String after = ba[1] == null ? null : ba[1].toUpperCase(Locale.ROOT);
                String before = ba[0] == null ? null : ba[0].toUpperCase(Locale.ROOT);
                boolean stateField = after != null && after.matches("[A-Z_]{3,}");
                String t = field + ": " + (ba[0] == null ? "∅" : ba[0]) + " → " + ba[1];
                if (after != null && ruleStates.contains(after)) {
                    // "só PENDING pode virar CONFIRMED": estado anterior fora da regra = violação
                    if (before != null && ruleStates.size() >= 2 && !ruleStates.contains(before)) {
                        forbidden.add(t);
                    } else {
                        transitions.add(t);
                    }
                } else if (before != null && ruleStates.contains(before)) {
                    transitions.add(t); // a pré-condição de estado da regra foi atendida
                } else if (stateField && !ruleStates.isEmpty()) {
                    boolean precondition = ruleText.contains(" so ") || ruleText.startsWith("so ") || ruleText.contains("apenas")
                            || ruleText.contains("somente") || ruleText.contains("pode ser");
                    (precondition ? forbidden : missedTarget).add(t);
                }
            });
        }
        if (!forbidden.isEmpty()) {
            return new String[] {"violada", "transição a partir de estado não permitido pela regra: " + String.join(", ", forbidden), "2.6"};
        }
        if (!transitions.isEmpty()) {
            return new String[] {"respeitada", "transição observada compatível com a regra: " + String.join(", ", transitions), "2.6"};
        }
        // regra CONDICIONADA a falha ("falha no provedor … deve ficar FAILED", "SPI fora do ar faz a
        // mensagem voltar para a fila"): sem falha no escopo, a condição não ocorreu — não é violação
        // (achado na stack alvo: Pix perfeito saía "apto com ressalvas" por isso)
        boolean failureConditional = TextFeatures.containsAny(ruleText, FAILURE_CONDITION);
        if (!missedTarget.isEmpty()) {
            if (failureConditional) {
                return new String[] {"inconclusiva", "a regra trata do caminho de falha e nenhuma falha ocorreu nesta execução"
                        + " — exercite o caso negativo (ex.: variação de mock)", "1.8"};
            }
            return new String[] {"violada", "o estado esperado pela regra (" + String.join("/", ruleStates)
                    + ") não foi alcançado: " + String.join(", ", missedTarget), "2.2"};
        }
        // comportamento documentado: CADA verbo citado precisa acontecer
        List<String> evidence = new ArrayList<>();
        if (ruleText.contains("grava") || ruleText.contains("salva") || ruleText.contains("persist")) {
            if (scope.stream().anyMatch(n -> n.mutationKind() != null && !"READ_ONLY".equals(n.mutationKind()))) {
                evidence.add("gravou dados");
            } else {
                return new String[] {"violada", "a documentação diz que grava, mas nenhuma escrita foi observada", "1.8"};
            }
        }
        if (ruleText.contains("publica") || ruleText.contains("fila") || ruleText.contains("evento") || ruleText.contains("notific")) {
            if (scope.stream().anyMatch(n -> n.producer() || n.kind() == NodeKind.SQS || n.kind() == NodeKind.SNS
                    || TextFeatures.fold(n.label()).contains("notific"))) {
                evidence.add("publicou/notificou");
            } else if (failureConditional) {
                return new String[] {"inconclusiva", "a publicação descrita acontece no caminho de falha, que não ocorreu nesta execução", "1.6"};
            } else {
                return new String[] {"violada", "a documentação diz que publica/notifica, mas nenhuma publicação foi observada", "1.8"};
            }
        }
        if (ruleText.contains("cobr") || ruleText.contains("billed") || ruleText.contains("marca")) {
            if (scope.stream().anyMatch(n -> "UPDATE".equals(n.mutationKind()))) {
                evidence.add("atualizou o registro");
            }
        }
        if (!evidence.isEmpty()) {
            return new String[] {"respeitada", "evidência observada: " + String.join(", ", evidence), "2.2"};
        }
        if (isGuardRule) {
            return new String[] {"inconclusiva", "guarda não foi acionada nesta execução (caminho feliz) — exercite o caso negativo", "1.6"};
        }
        return new String[] {"respeitada", "passos do termo concluíram sem erro", "1.5"};
    }

    private boolean isDescendant(NodeFact n, NodeFact ancestor) {
        String p = n.parentId();
        int guard = 0;
        while (p != null && guard++ < 64) {
            if (p.equals(ancestor.nodeId())) {
                return true;
            }
            NodeFact parent = facts.node(p);
            p = parent != null ? parent.parentId() : null;
        }
        return false;
    }

    private Answer logClass(Question q) {
        int idx;
        try {
            idx = Integer.parseInt(q.subject());
        } catch (NumberFormatException e) {
            return null;
        }
        LogFact l = idx >= 0 && idx < facts.logs().size() ? facts.logs().get(idx) : null;
        if (l == null) {
            return null;
        }
        Answer fact = QuestionCatalog.fact(q, facts);
        if (fact != null) {
            return fact.withEngine(Answer.ENGINE_DETERMINISTIC);
        }
        String msg = l.message() == null ? "" : l.message();
        String level = l.level() == null ? "INFO" : l.level().toUpperCase(Locale.ROOT);
        Map<String, Double> score = new LinkedHashMap<>();
        q.criteria().keySet().forEach(k -> score.put(k, 0.0));
        int biz = TextFeatures.countAny(msg, TextFeatures.BUSINESS_EVENT);
        int bizFail = TextFeatures.countAny(msg, TextFeatures.BUSINESS_FAILURE) + TextFeatures.countAny(msg, TextFeatures.BUSINESS_GUARD);
        int tech = TextFeatures.countAny(msg, TextFeatures.TECHNICAL_FAILURE)
                + (msg.contains("Exception") || msg.contains("\tat ") ? 1 : 0);
        String why;
        int skip = TextFeatures.countAny(msg, TextFeatures.IDEMPOTENT_SKIP);
        if (TextFeatures.isStackFrame(msg)) {
            score.put("diagnostico", 2.6);
            score.put("erro-tecnico", 1.4);
            why = "frame de stack trace — detalhe do erro registrado acima";
        } else if (skip > 0 && tech == 0 && TextFeatures.countAny(msg, TextFeatures.BUSINESS_FAILURE) == 0
                && !"ERROR".equals(level) && !msg.contains("Exception")) {
            score.put("evento-de-negocio", 2.6);
            score.put("erro-de-negocio", 1.0);
            why = "guarda de idempotência agiu (duplicado/reentrega ignorado sem efeito) — evento esperado, não erro";
        } else if ("WARN".equals(level) && tech == 0 && bizFail == 0 && !msg.contains("Exception")) {
            score.put("diagnostico", 2.0);
            score.put("evento-de-negocio", biz > 0 ? 1.6 : 0.6);
            why = "aviso sem assinatura de falha (técnica ou de regra)";
        } else if ("ERROR".equals(level) || "WARN".equals(level) || msg.contains("Exception")) {
            if (bizFail >= tech && bizFail > 0) {
                score.put("erro-de-negocio", 2.5);
                score.put("erro-tecnico", 1.2);
                why = "erro com vocabulário de regra/recusa";
            } else {
                score.put("erro-tecnico", 2.5);
                score.put("erro-de-negocio", 1.0);
                why = "erro com assinatura técnica";
            }
        } else if (biz > 0) {
            score.put("evento-de-negocio", 2.0 + Math.min(1.0, biz * 0.3));
            score.put("diagnostico", 1.0);
            why = "mensagem cita entidades/verbos do domínio";
        } else {
            score.put("diagnostico", 2.0);
            score.put("evento-de-negocio", 0.8);
            why = "mensagem técnica sem termo de negócio";
        }
        return choice(q, score, 0.6, why);
    }

    // ================================================================== motor léxico genérico

    private Answer lexical(Question q, String stateText) {
        Set<String> doc = TextFeatures.tokenSet(stateText);
        switch (q.type()) {
            case NOUL -> {
                Set<String> claim = TextFeatures.tokenSet(q.instructions());
                double cov = TextFeatures.coverage(claim, doc);
                boolean negated = TextFeatures.tokens(q.instructions()).stream().anyMatch(TextFeatures.NEGATIONS::contains);
                double p = 0.5 + (cov - 0.5) * 0.6;
                if (negated) {
                    p = 1 - p;
                }
                p = TextFeatures.round(Math.max(0.05, Math.min(0.95, p)));
                return new Answer(q.id(), q.type(), null, p, Math.abs(p - 0.5) * 2, Map.of(),
                        Answer.ENGINE_DETERMINISTIC, VERSION, "cobertura léxica " + Math.round(cov * 100) + "%");
            }
            case CHOICE -> {
                Map<String, Double> score = new LinkedHashMap<>();
                for (var e : q.criteria().entrySet()) {
                    Set<String> opt = TextFeatures.tokenSet(e.getKey().replace('-', ' ') + " " + e.getValue());
                    score.put(e.getKey(), TextFeatures.coverage(opt, doc) * 4);
                }
                return choice(q, score, 0.7, "similaridade léxica entre estado e critérios");
            }
            case SCORE -> {
                Map<String, Double> score = new LinkedHashMap<>();
                for (int i = 0; i < q.levels().size(); i++) {
                    Set<String> lvl = TextFeatures.tokenSet(q.levels().get(i));
                    score.put(String.valueOf(i), TextFeatures.coverage(lvl, doc) * 4);
                }
                Map<String, Double> probs = TextFeatures.softmax(score, 0.7);
                double expected = 0;
                for (var e : probs.entrySet()) {
                    expected += Integer.parseInt(e.getKey()) * e.getValue();
                }
                double top = probs.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                int idx = (int) Math.round(expected);
                return new Answer(q.id(), q.type(), q.levels().isEmpty() ? null : q.levels().get(Math.max(0, Math.min(q.levels().size() - 1, idx))),
                        TextFeatures.round(expected), TextFeatures.round(top), probs, Answer.ENGINE_DETERMINISTIC, VERSION,
                        "similaridade léxica com os níveis");
            }
            default -> throw new IllegalStateException();
        }
    }

    // ================================================================== helpers

    private static Answer choice(Question q, Map<String, Double> score, double temperature, String why) {
        Map<String, Double> probs = TextFeatures.softmax(score, temperature);
        String best = null;
        double bestP = -1;
        double second = 0;
        for (var e : probs.entrySet()) {
            if (e.getValue() > bestP) {
                second = Math.max(second, bestP);
                bestP = e.getValue();
                best = e.getKey();
            } else {
                second = Math.max(second, e.getValue());
            }
        }
        double confidence = TextFeatures.round(Math.max(0, Math.min(1, bestP - second * 0.5)));
        return new Answer(q.id(), Question.Type.CHOICE, best, TextFeatures.round(bestP), confidence, probs,
                Answer.ENGINE_DETERMINISTIC, VERSION, why);
    }

    private static Answer score(Question q, double value, String why, double confidence) {
        int n = q.levels().size();
        Map<String, Double> probs = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            probs.put(String.valueOf(i), TextFeatures.round(Math.max(0, 1 - Math.abs(i - value)) ));
        }
        double sum = probs.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0) {
            probs.replaceAll((k, v) -> TextFeatures.round(v / sum));
        }
        int idx = (int) Math.max(0, Math.min(n - 1, Math.round(value)));
        return new Answer(q.id(), Question.Type.SCORE, n == 0 ? null : q.levels().get(idx),
                TextFeatures.round(value), confidence, probs, Answer.ENGINE_DETERMINISTIC, VERSION, why);
    }

    private static String firstType(List<NodeFact> failed) {
        return failed.isEmpty() ? "?" : (failed.get(0).errorType() != null ? failed.get(0).errorType() : "erro");
    }

    /** Palavras em CAIXA ALTA da regra (estados de domínio: PENDING, CONFIRMED…). */
    /**
     * Estados citados na regra. Caixa alta sozinha não basta: "API de iniciação (API Gateway)",
     * "SPI", "KYC", "SQS" são siglas, não estados (achado na stack alvo: regra respeitada saía
     * "violada" porque "API" nunca aparecia como valor de status). Vale como estado o token que
     * aparece como valor observado nos deltas da execução ou que tem forma de enum de estado
     * (com "_" ou particípio/gerúndio: SETTLED, PENDING, APROVADO, IN_REVIEW).
     */
    /** Gatilhos de cláusula condicionada a falha (texto já sem acento — {@link TextFeatures#fold}). */
    private static final List<String> FAILURE_CONDITION = List.of(
            "falha", "fora do ar", "indisponi", "timeout", "reentrega", "nova tentativa", "tentativas", "erro ", " erro", "dlq");

    private Set<String> stateTokens(String text) {
        Set<String> observed = new java.util.HashSet<>();
        for (NodeFact n : facts.nodes()) {
            n.deltaValues().forEach((field, ba) -> {
                for (String v : ba) {
                    if (v != null && v.toUpperCase(Locale.ROOT).matches("[A-Z_]{3,}")) {
                        observed.add(v.toUpperCase(Locale.ROOT));
                    }
                }
            });
        }
        Set<String> out = new java.util.HashSet<>();
        for (String t : upperTokens(text)) {
            if (observed.contains(t) || t.contains("_") || t.matches(".*(ED|ING|ADO|ADA|IDO|IDA)")) {
                out.add(t);
            }
        }
        return out;
    }

    private static Set<String> upperTokens(String text) {
        Set<String> out = new java.util.HashSet<>();
        for (String t : text.split("[^A-Za-z_]+")) {
            if (t.length() >= 3 && t.equals(t.toUpperCase(Locale.ROOT))) {
                out.add(t);
            }
        }
        return out;
    }
}
