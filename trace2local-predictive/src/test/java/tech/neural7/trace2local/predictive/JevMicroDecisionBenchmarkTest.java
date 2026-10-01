package tech.neural7.trace2local.predictive;

import tech.neural7.trace2local.otel.OtelAttributeNames;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.Trigger;
import tech.neural7.trace2local.predictive.TraceBuilder.Spec;
import tech.neural7.trace2local.predictive.analyzers.SensitiveDataAnalyzer;
import tech.neural7.trace2local.predictive.decision.Answer;
import tech.neural7.trace2local.predictive.decision.DecisionEngine;
import tech.neural7.trace2local.predictive.decision.DeterministicJevModel;
import tech.neural7.trace2local.predictive.decision.ExecutionFacts;
import tech.neural7.trace2local.predictive.decision.IntelligenceConfig;
import tech.neural7.trace2local.predictive.decision.Question;
import tech.neural7.trace2local.predictive.decision.QuestionCatalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AVALIAÇÃO do Jev nas micro-decisões da lib (ADR-011 §avaliação) contra um
 * gabarito rotulado — Jev × Jev determinístico × cascata por limiar.
 *
 * <ul>
 *   <li>com {@code JEV_API_KEY}: chama a API real e GRAVA o cassete em
 *       {@code src/test/resources/jev-cassette-benchmark.jsonl} (só hash + resposta);</li>
 *   <li>sem chave: REPRODUZ o cassete — o mesmo número, offline, em qualquer CI
 *       ("uso determinístico dos modelos Jev");</li>
 *   <li>sem chave e sem cassete: mede só o determinístico.</li>
 * </ul>
 * Relatório: {@code target/benchmark-jev.md}.
 */
class JevMicroDecisionBenchmarkTest {

    private static final Path CASSETTE = Path.of("src", "test", "resources", "jev-cassette-benchmark.jsonl");
    private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");

    /** Um item rotulado: pergunta, estado, resposta esperada e a resposta determinística do produto. */
    record Item(String task, Question question, Map<String, String> state, String expected, Answer deterministic) {}

    @Test
    void jevVersusDeterministicOnLabeledMicroDecisions() throws Exception {
        String key = System.getenv("JEV_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("TRACE2LOCAL_JEV_API_KEY");
        }
        boolean live = key != null && !key.isBlank();
        boolean replay = !live && Files.isRegularFile(CASSETTE);
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("cassette", CASSETTE.toString());
        cfg.put("max-questions", "40");
        if (live) {
            cfg.put("api-key", key);
            cfg.put("mode", "record");
        } else {
            cfg.put("mode", replay ? "replay" : "deterministic");
        }
        DecisionEngine engine = new DecisionEngine(IntelligenceConfig.from(cfg::get));

        List<Item> items = new ArrayList<>();
        items.addAll(logItems());
        items.addAll(piiItems());
        items.addAll(iacItems());
        items.addAll(roleItems());
        items.addAll(outcomeItems());
        items.addAll(ruleItems());

        // agrupa por estado (uma requisição por estado/lote, como o produto faz)
        Map<String, Answer> modelAnswers = new LinkedHashMap<>();
        Map<Map<String, String>, List<Item>> byState = new LinkedHashMap<>();
        items.forEach(i -> byState.computeIfAbsent(i.state(), k -> new ArrayList<>()).add(i));
        long t0 = System.nanoTime();
        int requests = 0;
        for (var e : byState.entrySet()) {
            List<Question> qs = e.getValue().stream().map(Item::question).toList();
            Map<String, Answer> det = new LinkedHashMap<>();
            e.getValue().forEach(i -> det.put(i.question().id(), i.deterministic()));
            modelAnswers.putAll(engine.ask(e.getKey(), qs, (s, q) -> det));
            requests++;
        }
        double totalMs = (System.nanoTime() - t0) / 1e6;

        String report = report(items, modelAnswers, engine, live, replay, totalMs, requests);
        Path out = Path.of("target", "benchmark-jev.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println(report);

        // o determinístico é o piso garantido do produto — não pode regredir
        double detAcc = accuracy(items, i -> i.deterministic());
        assertThat(detAcc).as("acurácia do Jev determinístico no gabarito").isGreaterThanOrEqualTo(0.75);
    }

    // ================================================================== gabarito

    private static List<Item> logItems() {
        String[][] lines = {
                {"erro-de-negocio", "ERROR", "Pedido ORDER-9 recusado: excede o limite de crédito do cliente"},
                {"erro-de-negocio", "WARN", "Payment rejected: insufficient balance for account A-12"},
                {"erro-de-negocio", "ERROR", "Validação falhou: valor do Pix deve ser positivo"},
                {"erro-de-negocio", "ERROR", "Order ORDER-4 cannot be confirmed: status is CANCELLED"},
                {"erro-de-negocio", "WARN", "Pix duplicado recusado pela guarda de idempotência"},
                // linhas REAIS (Lambda + CloudWatch do LocalStack) — viraram gabarito após o loop de validação
                {"erro-de-negocio", "INFO", "ordem recusada: ORDER-C3 excede o limite de crédito: java.lang.IllegalStateException"},
                {"evento-de-negocio", "WARN", "WARN chave PAY-777 já processada — reentrega ignorada sem efeito colateral"},
                {"diagnostico", "INFO", "\tat tech.neural7.trace2local.examples.lambda.OrderProcessor.handle(OrderProcessor.java:87)"},
                {"diagnostico", "INFO", "\tat java.base/java.lang.reflect.Method.invoke(Unknown Source)"},
                {"erro-de-negocio", "WARN", "Coupon BLACKFRIDAY expired for customer C-10"},
                {"erro-de-negocio", "ERROR", "Estoque insuficiente para o SKU 123"},
                {"erro-de-negocio", "ERROR", "Transferência acima do limite diário permitido"},
                {"erro-tecnico", "ERROR", "java.net.SocketTimeoutException: Read timed out"},
                {"erro-tecnico", "ERROR", "Unable to execute HTTP request: Connect to localhost:4566 failed: Connection refused"},
                {"erro-tecnico", "ERROR", "java.lang.NullPointerException at OrderService.java:88"},
                {"erro-tecnico", "ERROR", "ResourceNotFoundException: Requested resource not found (Table: orderz)"},
                {"erro-tecnico", "WARN", "ThrottlingException: Rate exceeded for SendMessage"},
                {"erro-tecnico", "ERROR", "Could not write JSON: Infinite recursion (StackOverflowError)"},
                {"erro-tecnico", "ERROR", "AccessDeniedException: not authorized to perform dynamodb:PutItem"},
                {"erro-tecnico", "ERROR", "Task timed out after 30.03 seconds"},
                {"evento-de-negocio", "INFO", "Pedido ORDER-1 criado para o cliente C-1 no valor de 99,90"},
                {"evento-de-negocio", "INFO", "Payment P-77 confirmed and receipt sent to payer"},
                {"evento-de-negocio", "INFO", "Order ORDER-3 marked as BILLED"},
                {"evento-de-negocio", "INFO", "Pix recebido do pagador e confirmado"},
                {"evento-de-negocio", "INFO", "Cliente C-9 cadastrado no plano gold"},
                {"evento-de-negocio", "INFO", "Invoice INV-55 issued for order ORDER-5"},
                {"evento-de-negocio", "INFO", "Estorno do pedido ORDER-4 concluído"},
                {"evento-de-negocio", "INFO", "Notificação de entrega enviada ao cliente C-2"},
                {"diagnostico", "INFO", "Starting OrderServiceApplication using Java 21.0.10 with PID 4242"},
                {"diagnostico", "INFO", "HikariPool-1 - Start completed."},
                {"diagnostico", "INFO", "Tomcat started on port 8080 (http) with context path '/'"},
                {"diagnostico", "DEBUG", "cache warm-up finished in 120 ms"},
                {"diagnostico", "DEBUG", "Loaded 34 bean definitions from classpath"},
                {"diagnostico", "INFO", "Retrying request, attempt 2 of 3"},
                {"diagnostico", "DEBUG", "Connection pool stats: size=10 idle=8 active=2"},
                {"diagnostico", "INFO", "Initializing Spring DispatcherServlet 'dispatcherServlet'"},
        };
        TraceBuilder t = TraceBuilder.execution("logbench", T0).trigger(Trigger.LAMBDA_EVENT);
        t.root(NodeKind.LAMBDA, "order-processor", 0, 100).attr(OtelAttributeNames.FAAS_NAME, "order-processor");
        Execution e = t.build();
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            logs.add(new LogEntry(T0.plusMillis(i), lines[i][1], "app", lines[i][2], e.traceId(), null, null,
                    "/aws/lambda/order-processor", "s", LogEntry.LogSource.APP));
        }
        ExecutionFacts f = ExecutionFacts.of(e, logs, Map.of());
        List<Question> qs = QuestionCatalog.build(f, new QuestionCatalog.Limits(0, 0, 100)).stream()
                .filter(q -> QuestionCatalog.LOG_CLASS.equals(q.family())).toList();
        Map<String, Answer> det = new DeterministicJevModel(f).decide(Map.of(), qs);
        Map<String, String> state = Map.of("context", "Log lines emitted by a Java service (AWS Lambda order-processor) during one execution");
        List<Item> out = new ArrayList<>();
        for (Question q : qs) {
            int idx = Integer.parseInt(q.subject());
            out.add(new Item("classificação de log", q, state, lines[idx][0], det.get(q.id())));
        }
        return out;
    }

    private static List<Item> piiItems() {
        String[][] fields = {
                {"customerName", "sim"}, {"nomeCompleto", "sim"}, {"telefone", "sim"}, {"phoneNumber", "sim"},
                {"endereco", "sim"}, {"shippingAddress", "sim"}, {"dataNascimento", "sim"}, {"birthDate", "sim"},
                {"rg", "sim"}, {"motherName", "sim"}, {"salary", "sim"}, {"documentoIdentidade", "sim"},
                {"tableName", "não"}, {"queueName", "não"}, {"orderId", "não"}, {"totalAmount", "não"},
                {"status", "não"}, {"createdAt", "não"}, {"sku", "não"}, {"quantity", "não"}, {"currency", "não"},
                {"region", "não"}, {"retryCount", "não"}, {"correlationId", "não"}, {"plan", "não"}, {"featureFlag", "não"},
                {"target", "não"}, {"charge", "não"},
        };
        List<Item> out = new ArrayList<>();
        int i = 0;
        for (String[] f : fields) {
            String id = "pii_" + i++;
            Question q = Question.noul(id, "analyzer", id, "The JSON field '" + f[0] + "' most likely holds personal data about a person (PII).");
            boolean lexical = SensitiveDataAnalyzer.isPiiKey(f[0]);
            Answer det = new Answer(id, Question.Type.NOUL, null, lexical ? 0.8 : 0.3, lexical ? 0.6 : 0.4, Map.of(),
                    Answer.ENGINE_DETERMINISTIC, DeterministicJevModel.VERSION, "léxico");
            out.add(new Item("dado pessoal (PII)", q, Map.of("field", f[0], "where", "request", "component", "POST /customers"),
                    f[1], det));
        }
        return out;
    }

    private static List<Item> iacItems() {
        String[][] diffs = {
                {"lambda_memory_mb", "512", "1024", "intencional"},
                {"log_level", "DEBUG", "INFO", "intencional"},
                {"min_capacity", "1", "4", "intencional"},
                {"retention_days", "1", "35", "intencional"},
                {"deletion_protection", "false", "true", "intencional"},
                {"reserved_concurrency", "5", "50", "intencional"},
                {"oauth_scope", "orders.read orders.write", "orders.read", "erro"},
                {"api_version", "v2", "v1", "erro"},
                {"encryption_enabled", "true", "false", "erro"},
                {"payment_api_url", "https://api.psp.com/v2", "https://sandbox.psp.com/v2", "erro"},
                {"ssl_policy", "TLS-1-2", "TLS-1-0", "erro"},
                {"max_retries", "3", "0", "erro"},
        };
        List<Item> out = new ArrayList<>();
        int i = 0;
        for (String[] d : diffs) {
            String id = "iac_" + i++;
            Question q = Question.noul(id, "analyzer", id, "This difference between environments is an intentional, "
                    + "environment-specific setting (for example production sizing) rather than a configuration mistake.");
            String k = d[0];
            boolean sizing = k.contains("memory") || k.contains("concurren") || k.contains("capacity")
                    || k.contains("replica") || k.contains("min_") || k.contains("max_") || k.contains("retention");
            Answer det = new Answer(id, Question.Type.NOUL, null, sizing ? 0.75 : 0.3, sizing ? 0.5 : 0.4, Map.of(),
                    Answer.ENGINE_DETERMINISTIC, DeterministicJevModel.VERSION, "regra de dimensionamento");
            out.add(new Item("divergência IaC intencional?", q, Map.of("setting", k, "dev", d[1], "prod", d[2]),
                    d[3].equals("intencional") ? "sim" : "não", det));
        }
        return out;
    }

    private static List<Item> roleItems() {
        String[][] labels = {
                {"IdempotencyGuard", "regra-de-negocio"}, {"ValidateOrder", "regra-de-negocio"},
                {"CreditLimitPolicy.check", "regra-de-negocio"}, {"FraudRules.evaluate", "regra-de-negocio"},
                {"ConfirmarPagamento", "regra-de-negocio"}, {"OrderService.create", "orquestracao"},
                {"ProcessarPagamento", "orquestracao"}, {"CheckoutFacade.execute", "orquestracao"},
                {"ReportService.build", "orquestracao"}, {"EventPublisher.publish", "mensageria"},
                {"BillingConsumer.onMessage", "consumo-assincrono"}, {"PartnerClient.send", "integracao-externa"},
        };
        TraceBuilder t = TraceBuilder.execution("rolebench", T0);
        Spec api = TraceBuilder.http(null, t, "POST", "/orders", 0, 500);
        for (int i = 0; i < labels.length; i++) {
            api.child(NodeKind.BUSINESS, labels[i][0], 10 + i * 30L, 20);
        }
        Execution e = t.build();
        ExecutionFacts f = ExecutionFacts.of(e, List.of(), Map.of());
        Map<String, String> expectedByNode = new LinkedHashMap<>();
        for (ExecutionFacts.NodeFact n : f.nodes()) {
            for (String[] l : labels) {
                if (l[0].equals(n.label())) {
                    expectedByNode.put(n.nodeId(), l[1]);
                }
            }
        }
        List<Question> qs = QuestionCatalog.build(f, new QuestionCatalog.Limits(40, 0, 0)).stream()
                .filter(q -> QuestionCatalog.NODE_ROLE.equals(q.family()) && expectedByNode.containsKey(q.subject())).toList();
        Map<String, Answer> det = new DeterministicJevModel(f).decide(Map.of(), qs);
        Map<String, String> state = tech.neural7.trace2local.predictive.assistant.StateBuilder.of(f, IntelligenceConfig.Egress.STRUCTURAL);
        List<Item> out = new ArrayList<>();
        for (Question q : qs) {
            out.add(new Item("papel arquitetural", q, state, expectedByNode.get(q.subject()), det.get(q.id())));
        }
        return out;
    }

    private static List<Item> outcomeItems() {
        Object[][] cases = {
                {"recusa-protegida", ExecutionStatus.COMPLETED, "IdempotencyGuard", "software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException", "The conditional request failed"},
                {"falha-de-negocio", ExecutionStatus.FAILED, "OrderProcessor", "java.lang.IllegalStateException", "ordem recusada: ORDER-1 excede o limite de crédito"},
                {"falha-tecnica", ExecutionStatus.FAILED, "PartnerClient", "java.net.SocketTimeoutException", "Read timed out"},
                {"falha-tecnica", ExecutionStatus.FAILED, "OrderRepository", "software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException", "Requested resource not found"},
                {"falha-de-negocio", ExecutionStatus.FAILED, "PaymentValidator", "javax.validation.ValidationException", "amount must be positive"},
                {"falha-tecnica", ExecutionStatus.FAILED, "OrderService", "java.lang.NullPointerException", "Cannot invoke \"String.length()\" because \"x\" is null"},
                {"falha-de-negocio", ExecutionStatus.FAILED, "ConfirmarPagamento", "tech.acme.PaymentConflictException", "Pagamento já CANCELADO não pode ser confirmado"},
                {"falha-tecnica", ExecutionStatus.FAILED, "OrderRepository", "software.amazon.awssdk.services.dynamodb.model.DynamoDbException", "AccessDeniedException: not authorized"},
                {"falha-de-negocio", ExecutionStatus.FAILED, "CheckoutService", "java.lang.IllegalStateException", "estoque insuficiente para o SKU 123"},
                {"falha-tecnica", ExecutionStatus.FAILED, "EventPublisher", "software.amazon.awssdk.core.exception.SdkClientException", "Unable to execute HTTP request: Connection refused"},
        };
        List<Item> out = new ArrayList<>();
        int i = 0;
        for (Object[] c : cases) {
            TraceBuilder t = TraceBuilder.execution("oc-" + i++, T0).status((ExecutionStatus) c[1]);
            Spec api = TraceBuilder.http(null, t, "POST", "/flow", 0, 200);
            Spec step = api.child(NodeKind.BUSINESS, (String) c[2], 10, 150);
            TraceBuilder.dynamo(step, "PutItem", "orders", 20, 30).error((String) c[3], (String) c[4]);
            Execution e = t.build();
            ExecutionFacts f = ExecutionFacts.of(e, List.of(), Map.of());
            Question q = QuestionCatalog.build(f, new QuestionCatalog.Limits(0, 0, 0)).stream()
                    .filter(x -> QuestionCatalog.EXEC_OUTCOME.equals(x.family())).findFirst().orElseThrow();
            Answer det = new DeterministicJevModel(f).decide(Map.of(), List.of(q)).get(q.id());
            Map<String, String> state = tech.neural7.trace2local.predictive.assistant.StateBuilder.of(f, IntelligenceConfig.Egress.STRUCTURAL);
            out.add(new Item("desfecho da execução", q, state, (String) c[0], det));
        }
        return out;
    }

    /** Regras/comportamentos do glossário × evidência da execução (visão executiva / homologação). */
    private static List<Item> ruleItems() {
        List<Item> out = new ArrayList<>();
        // 1) guarda de idempotência acionada ⇒ respeitada
        out.add(rule("IdempotencyGuard", "Guarda de idempotência — a mesma chave só grava uma vez; duplicados são recusados.",
                "respeitada", t -> {
                    Spec fn = t.root(NodeKind.LAMBDA, "idempotent-processor", 0, 60).attr(OtelAttributeNames.FAAS_NAME, "idempotent-processor");
                    Spec g = fn.child(NodeKind.BUSINESS, "IdempotencyGuard", 5, 50);
                    TraceBuilder.dynamo(g, "PutItem", "idempotency", 10, 30).error(
                            "software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException", "The conditional request failed");
                }));
        // 2) só PENDING pode ser confirmado — PENDING → CONFIRMED ⇒ respeitada
        out.add(rule("ConfirmarPagamento", "Confirma o Pix (regra: só um pagamento PENDING pode ser confirmado).", "respeitada", t -> {
            Spec api = TraceBuilder.http(null, t, "POST", "/payments/{id}/confirm", 0, 80);
            Spec b = api.child(NodeKind.BUSINESS, "ConfirmarPagamento", 5, 70);
            TraceBuilder.dynamo(b, "UpdateItem", "payments", 10, 30).mutation(tech.neural7.trace2local.model.MutationKind.UPDATE,
                    "payments", "PIX-1", "{\"status\":{\"S\":\"PENDING\"}}", "{\"status\":{\"S\":\"CONFIRMED\"}}",
                    "status", "\"PENDING\"", "\"CONFIRMED\"");
        }));
        // 3) mesma regra — CANCELLED → CONFIRMED ⇒ violada
        out.add(rule("ConfirmarPagamento", "Confirma o Pix (regra: só um pagamento PENDING pode ser confirmado).", "violada", t -> {
            Spec api = TraceBuilder.http(null, t, "POST", "/payments/{id}/confirm", 0, 80);
            Spec b = api.child(NodeKind.BUSINESS, "ConfirmarPagamento", 5, 70);
            TraceBuilder.dynamo(b, "UpdateItem", "payments", 10, 30).mutation(tech.neural7.trace2local.model.MutationKind.UPDATE,
                    "payments", "PIX-2", "{\"status\":{\"S\":\"CANCELLED\"}}", "{\"status\":{\"S\":\"CONFIRMED\"}}",
                    "status", "\"CANCELLED\"", "\"CONFIRMED\"");
        }));
        // 4) documentado: grava e publica — fez os dois ⇒ respeitada
        out.add(rule("order-processor", "Função que recebe o pedido, grava no DynamoDB e publica o evento na fila.", "respeitada", t -> {
            Spec fn = t.root(NodeKind.LAMBDA, "order-processor", 0, 100).attr(OtelAttributeNames.FAAS_NAME, "order-processor");
            TraceBuilder.dynamo(fn, "PutItem", "orders", 10, 30).mutation(tech.neural7.trace2local.model.MutationKind.CREATE,
                    "orders", "ORDER-1", null, "{\"pk\":{\"S\":\"ORDER-1\"}}");
            TraceBuilder.sqsPublish(fn, "orders-queue", 50, 10);
        }));
        // 5) documentado: grava e publica — só gravou ⇒ violada
        out.add(rule("order-processor", "Função que recebe o pedido, grava no DynamoDB e publica o evento na fila.", "violada", t -> {
            Spec fn = t.root(NodeKind.LAMBDA, "order-processor", 0, 100).attr(OtelAttributeNames.FAAS_NAME, "order-processor");
            TraceBuilder.dynamo(fn, "PutItem", "orders", 10, 30).mutation(tech.neural7.trace2local.model.MutationKind.CREATE,
                    "orders", "ORDER-2", null, "{\"pk\":{\"S\":\"ORDER-2\"}}");
        }));
        // 6) cobra e marca BILLED — chegou a BILLED ⇒ respeitada
        out.add(rule("order-billing", "Consumidor que cobra o pedido e o marca como BILLED.", "respeitada", t -> {
            Spec fn = t.root(NodeKind.LAMBDA, "order-billing", 0, 100).attr(OtelAttributeNames.FAAS_NAME, "order-billing");
            TraceBuilder.dynamo(fn, "UpdateItem", "orders", 10, 30).mutation(tech.neural7.trace2local.model.MutationKind.UPDATE,
                    "orders", "ORDER-3", "{\"status\":{\"S\":\"CONFIRMED\"}}", "{\"status\":{\"S\":\"BILLED\"}}",
                    "status", "\"CONFIRMED\"", "\"BILLED\"");
        }));
        // 7) cobra e marca BILLED — terminou em PAYMENT_FAILED ⇒ violada
        out.add(rule("order-billing", "Consumidor que cobra o pedido e o marca como BILLED.", "violada", t -> {
            Spec fn = t.root(NodeKind.LAMBDA, "order-billing", 0, 100).attr(OtelAttributeNames.FAAS_NAME, "order-billing");
            TraceBuilder.dynamo(fn, "UpdateItem", "orders", 10, 30).mutation(tech.neural7.trace2local.model.MutationKind.UPDATE,
                    "orders", "ORDER-4", "{\"status\":{\"S\":\"CONFIRMED\"}}", "{\"status\":{\"S\":\"PAYMENT_FAILED\"}}",
                    "status", "\"CONFIRMED\"", "\"PAYMENT_FAILED\"");
        }));
        // 8) regra de pedido sem o passo na execução ⇒ não exercitada
        out.add(rule("EstornarPedido", "Estorno só pode ocorrer para pedido BILLED.", "nao-exercitada", t -> {
            Spec api = TraceBuilder.http(null, t, "GET", "/orders/{id}", 0, 40);
            TraceBuilder.dynamo(api, "GetItem", "orders", 5, 20).mutation(tech.neural7.trace2local.model.MutationKind.READ_ONLY,
                    "orders", "ORDER-5", null, null);
        }));
        return out;
    }

    private static int ruleSeq;

    private static Item rule(String term, String text, String expected, java.util.function.Consumer<TraceBuilder> build) {
        TraceBuilder t = TraceBuilder.execution("rule-" + (ruleSeq++), T0).trigger(Trigger.LAMBDA_EVENT);
        build.accept(t);
        Execution e = t.build();
        ExecutionFacts f = ExecutionFacts.of(e, List.of(), Map.of(term.toLowerCase(Locale.ROOT), text));
        Question q = QuestionCatalog.build(f, QuestionCatalog.Limits.DEFAULT).stream()
                .filter(x -> QuestionCatalog.RULE_VERDICT.equals(x.family())).findFirst()
                .orElseGet(() -> Question.choice("rule_R1", QuestionCatalog.RULE_VERDICT, "R1", "Business rule '" + term + "': \""
                        + text + "\". Based only on the execution evidence, what is the verdict?", QuestionCatalog.VERDICT));
        Answer det = f.rules().isEmpty()
                ? new Answer(q.id(), Question.Type.CHOICE, "nao-exercitada", 1.0, 1.0, Map.of(), Answer.ENGINE_DETERMINISTIC,
                DeterministicJevModel.VERSION, "termo não casa nenhum passo")
                : new DeterministicJevModel(f).decide(Map.of(), List.of(q)).get(q.id());
        Map<String, String> state = new LinkedHashMap<>(tech.neural7.trace2local.predictive.assistant.StateBuilder.of(f,
                IntelligenceConfig.Egress.VALUES));
        state.put("business_glossary", "R1 " + term + ": " + text);
        return new Item("veredito de regra (homologação)", q, state, expected, det);
    }

    // ================================================================== métricas

    private static String label(Answer a) {
        if (a == null) {
            return "∅";
        }
        return a.type() == Question.Type.NOUL ? (a.yes() ? "sim" : "não") : String.valueOf(a.choice());
    }

    private static double accuracy(List<Item> items, java.util.function.Function<Item, Answer> pick) {
        int ok = 0;
        for (Item i : items) {
            if (i.expected().equals(label(pick.apply(i)))) {
                ok++;
            }
        }
        return items.isEmpty() ? 0 : (double) ok / items.size();
    }

    private static String report(List<Item> items, Map<String, Answer> model, DecisionEngine engine, boolean live,
                                 boolean replay, double totalMs, int requests) {
        StringBuilder sb = new StringBuilder("# Benchmark Jev — micro-decisões da lib\n\n");
        sb.append("Fonte das respostas do modelo: **").append(live ? "Jev API ao vivo (gravando cassete)" : replay ? "cassete do Jev (replay determinístico, offline)" : "sem Jev (só determinístico)")
                .append("** · ").append(items.size()).append(" itens rotulados · ").append(requests).append(" requisições · ")
                .append(String.format(Locale.ROOT, "%.0f ms no total", totalMs)).append("\n\n");
        Map<String, List<Item>> byTask = new LinkedHashMap<>();
        items.forEach(i -> byTask.computeIfAbsent(i.task(), k -> new ArrayList<>()).add(i));
        double[] taus = {0.5, 0.7, 0.8, 0.9};
        sb.append("| tarefa | n | determinístico | Jev | cascata τ=0,5 | τ=0,7 | τ=0,8 | τ=0,9 | Jev conf≥0,9 (n / acerto) | **política do produto** |\n|---|---|---|---|---|---|---|---|---|---|\n");
        List<Item> all = new ArrayList<>();
        byTask.forEach((task, list) -> {
            all.addAll(list);
            sb.append(row(task, list, model, taus));
        });
        sb.append(row("**TOTAL**", all, model, taus));
        sb.append("\n## Erros do Jev (para calibrar a fusão)\n\n| tarefa | pergunta | esperado | Jev | conf. | determinístico |\n|---|---|---|---|---|---|\n");
        for (Item i : all) {
            Answer a = model.get(i.question().id());
            boolean fromJev = a != null && !Answer.ENGINE_DETERMINISTIC.equals(a.engine());
            if (fromJev && !i.expected().equals(label(a))) {
                String q = i.question().instructions();
                sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %.2f | %s |%n", i.task(),
                        q.length() > 90 ? q.substring(0, 90) + "…" : q, i.expected(), label(a), a.decisiveness(), label(i.deterministic())));
            }
        }
        sb.append("\nStatus do motor: ").append(engine.status()).append('\n');
        return sb.toString();
    }

    private static String row(String task, List<Item> list, Map<String, Answer> model, double[] taus) {
        double det = accuracy(list, Item::deterministic);
        boolean anyJev = list.stream().anyMatch(i -> model.get(i.question().id()) != null
                && !Answer.ENGINE_DETERMINISTIC.equals(model.get(i.question().id()).engine()));
        double jev = anyJev ? accuracy(list, i -> model.get(i.question().id())) : Double.NaN;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "| %s | %d | %.2f | %s |", task, list.size(), det,
                Double.isNaN(jev) ? "—" : String.format(Locale.ROOT, "%.2f", jev)));
        for (double tau : taus) {
            double c = accuracy(list, i -> {
                Answer a = model.get(i.question().id());
                boolean fromJev = a != null && !Answer.ENGINE_DETERMINISTIC.equals(a.engine());
                return fromJev && a.decisiveness() >= tau ? a : i.deterministic();
            });
            sb.append(String.format(Locale.ROOT, " %.2f |", c));
        }
        int n = 0;
        int ok = 0;
        for (Item i : list) {
            Answer a = model.get(i.question().id());
            if (a != null && !Answer.ENGINE_DETERMINISTIC.equals(a.engine()) && a.decisiveness() >= 0.9) {
                n++;
                if (i.expected().equals(label(a))) {
                    ok++;
                }
            }
        }
        sb.append(n == 0 ? " — |" : String.format(Locale.ROOT, " %d / %.2f |", n, (double) ok / n));
        double policy = accuracy(list, i -> {
            Answer a = model.get(i.question().id());
            boolean fromJev = a != null && !Answer.ENGINE_DETERMINISTIC.equals(a.engine());
            return fromJev ? tech.neural7.trace2local.predictive.decision.FusionPolicy.choose(i.question(), a, i.deterministic())
                    : i.deterministic();
        });
        sb.append(String.format(Locale.ROOT, " **%.2f** |", policy));
        return sb.append('\n').toString();
    }
}
