package tech.neural7.trace2local.predictive;

import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.otel.OtelAttributeNames;
import tech.neural7.trace2local.model.ExecutionStatus;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.model.MutationKind;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.Trigger;
import tech.neural7.trace2local.predictive.TraceBuilder.Spec;
import tech.neural7.trace2local.predictive.project.ProjectSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static tech.neural7.trace2local.predictive.TraceBuilder.dynamo;
import static tech.neural7.trace2local.predictive.TraceBuilder.http;
import static tech.neural7.trace2local.predictive.TraceBuilder.lambda;
import static tech.neural7.trace2local.predictive.TraceBuilder.sqsPublish;

/**
 * DATASET DE VALIDAÇÃO das Regras Preditivas (ADR-013 §14): cenários
 * reproduzíveis com problemas INSERIDOS DE PROPÓSITO (e cenários-controle sem
 * problema, para medir falso positivo). Cada cenário declara exatamente quais
 * ids de insight DEVEM aparecer — nem mais, nem menos.
 */
public final class Scenarios {

    public enum Stage { EXECUTION, CORPUS, PROJECT }

    /** Um cenário: histórico prévio, alvo, logs, projeto e o gabarito. */
    public record Scenario(String name, String problem, Stage stage, List<Execution> priors, Execution target,
                           Map<String, List<LogEntry>> logs, ProjectSnapshot project, ProjectWriter projectFiles,
                           Set<String> expected) {}

    @FunctionalInterface
    public interface ProjectWriter {
        void write(Path root) throws IOException;
    }

    private static final Instant NOW = Instant.now().minusSeconds(60);
    private static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));

    private Scenarios() {}

    public static List<Scenario> all() {
        List<Scenario> s = new ArrayList<>();
        s.add(slowQueue());
        s.add(fastQueue());
        s.add(nPlusOne());
        s.add(redundantReads());
        s.add(overwriteWithoutGuard());
        s.add(duplicateConsumption());
        s.add(guardedDuplicate());
        s.add(inferredSqlTemplateKey());
        s.add(regression());
        s.add(outlier());
        s.add(missingResilience());
        s.add(resilienceOk());
        s.add(timeout());
        s.add(sensitiveLogs());
        s.add(piiPayload());
        s.add(piiAlreadyMasked());
        s.add(urlSecret());
        s.add(hotspot());
        s.add(errorTrend());
        s.add(consumerStopped());
        s.add(firstOrphan());
        s.add(shapeChange());
        s.add(terraformDrift());
        s.add(terraformCoherent());
        s.add(coverageLow());
        s.add(coverageOk());
        s.add(happyPath());
        s.add(historicQueueAnomaly());
        // controles "difíceis" (perto do limiar) — medem falso positivo de verdade
        s.add(fewDistinctReads());
        s.add(batchRead());
        s.add(noisyButStable());
        s.add(localstackClient());
        s.add(innocentLogs());
        s.add(technicalNames());
        // regressão vinda do CASO REAL (LocalStack 4.2 + DynamoDbDeltaInterceptor):
        // criação de chave nova chega com before = {} (objeto vazio), não null
        s.add(freshCreateWithEmptyBefore());
        return s;
    }

    // ================================================================== performance assíncrona

    static Execution orderFlow(String id, Instant at, long wait) {
        TraceBuilder t = TraceBuilder.execution(id, at).trigger(Trigger.UI_DISPATCH);
        Spec api = http(null, t, "POST", "/orders", 0, 120);
        Spec svc = api.child(NodeKind.BUSINESS, "OrderService.create", 10, 100)
                .attr(OtelAttributeNames.CODE_NAMESPACE, "com.acme.orders.OrderService");
        dynamo(svc, "PutItem", "orders", 20, 40)
                .mutation(MutationKind.CREATE, "orders", id.toUpperCase() + "-ORD", null,
                        "{\"pk\":{\"S\":\"" + id + "\"},\"status\":{\"S\":\"PENDING\"}}");
        Spec q = sqsPublish(svc, "order-events", 70, 20);
        Spec consumer = lambda(q, "order-billing", "req-" + id, 90 + wait, 870);
        dynamo(consumer, "UpdateItem", "orders", 90 + wait + 100, 120)
                .mutation(MutationKind.UPDATE, "orders", id.toUpperCase() + "-ORD",
                        "{\"status\":{\"S\":\"PENDING\"}}", "{\"status\":{\"S\":\"BILLED\"}}",
                        "status", "\"PENDING\"", "\"BILLED\"");
        return t.build();
    }

    static Scenario slowQueue() {
        return new Scenario("sqs-lenta", "Espera de 4,1 s entre publicação na fila e consumo pela Lambda",
                Stage.EXECUTION, List.of(), orderFlow("slow-q", NOW, 4100), Map.of(), null, null,
                Set.of("PERF-ASYNC-001"));
    }

    static Scenario fastQueue() {
        return new Scenario("sqs-rapida", "Controle: mesmo fluxo com 40 ms de espera",
                Stage.EXECUTION, List.of(), orderFlow("fast-q", NOW, 40), Map.of(), null, null, Set.of());
    }

    static Scenario historicQueueAnomaly() {
        List<Execution> priors = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            priors.add(orderFlow("hq-" + i, NOW.minusSeconds(600 - i * 10L), 55 + i * 2));
        }
        return new Scenario("gargalo-assincrono-historico",
                "Espera de 380 ms — abaixo do limiar absoluto, mas ~6× a mediana histórica da mesma fila",
                Stage.EXECUTION, priors, orderFlow("hq-target", NOW, 380), Map.of(), null, null,
                Set.of("PERF-ASYNC-001"));
    }

    // ================================================================== banco

    static Scenario nPlusOne() {
        TraceBuilder t = TraceBuilder.execution("n1", NOW);
        Spec api = http(null, t, "GET", "/orders/report", 0, 900);
        Spec svc = api.child(NodeKind.BUSINESS, "ReportService.build", 5, 890);
        dynamo(svc, "Query", "orders", 10, 20)
                .mutation(MutationKind.READ_ONLY, "orders", "customerId=C-1", null, null);
        for (int i = 0; i < 120; i++) {
            dynamo(svc, "GetItem", "customers", 30 + i * 7L, 6)
                    .mutation(MutationKind.READ_ONLY, "customers", "C-" + i, null, null);
        }
        return new Scenario("n-mais-1", "121 consultas para 120 entidades (laço com GetItem)", Stage.EXECUTION,
                List.of(), t.build(), Map.of(), null, null, Set.of("DB-N1-001"));
    }

    static Scenario redundantReads() {
        TraceBuilder t = TraceBuilder.execution("red", NOW);
        Spec api = http(null, t, "GET", "/orders/{id}", 0, 200);
        Spec c1 = api.child(NodeKind.BUSINESS, "OrderController.get", 5, 25);
        dynamo(c1, "GetItem", "orders", 10, 15).mutation(MutationKind.READ_ONLY, "orders", "ORDER-9", null, null);
        Spec c2 = api.child(NodeKind.BUSINESS, "PricingService.price", 30, 90);
        dynamo(c2, "GetItem", "orders", 35, 15).mutation(MutationKind.READ_ONLY, "orders", "ORDER-9", null, null);
        Spec c3 = api.child(NodeKind.BUSINESS, "AuditService.log", 130, 60);
        dynamo(c3, "GetItem", "orders", 135, 15).mutation(MutationKind.READ_ONLY, "orders", "ORDER-9", null, null);
        return new Scenario("consultas-redundantes", "Mesmo GetItem de ORDER-9 em três camadas", Stage.EXECUTION,
                List.of(), t.build(), Map.of(), null, null, Set.of("DB-RED-001"));
    }

    // ================================================================== idempotência

    static Execution creation(String id, Instant at, String key, String before) {
        TraceBuilder t = TraceBuilder.execution(id, at).trigger(Trigger.LAMBDA_EVENT);
        Spec fn = t.root(NodeKind.LAMBDA, "order-processor", 0, 80).attr(OtelAttributeNames.FAAS_NAME, "order-processor")
                .attr(OtelAttributeNames.FAAS_INVOCATION_ID, "req-" + id);
        dynamo(fn, "PutItem", "orders", 10, 30).mutation(MutationKind.CREATE, "orders", key, before,
                "{\"pk\":{\"S\":\"" + key + "\"},\"total\":{\"N\":\"10\"}}");
        return t.build();
    }

    static Scenario overwriteWithoutGuard() {
        Execution first = creation("ow-1", NOW.minusSeconds(30), "ORDER-7", null);
        Execution again = creation("ow-2", NOW, "ORDER-7", "{\"pk\":{\"S\":\"ORDER-7\"},\"total\":{\"N\":\"10\"}}");
        return new Scenario("ausencia-de-idempotencia", "Reprocessamento sobrescreve ORDER-7 sem ConditionExpression",
                Stage.EXECUTION, List.of(first), again, Map.of(), null, null, Set.of("IDEM-001"));
    }

    static Scenario freshCreateWithEmptyBefore() {
        Execution first = creation("fc-1", NOW.minusSeconds(30), "ORDER-11", "{}");
        Execution second = creation("fc-2", NOW, "ORDER-12", "{}");
        return new Scenario("controle-criacao-nova-before-vazio", "Chaves novas (ORDER-11, ORDER-12) com before = {} do interceptor",
                Stage.EXECUTION, List.of(first), second, Map.of(), null, null, Set.of());
    }

    static Execution billing(String id, Instant at, String key) {
        TraceBuilder t = TraceBuilder.execution(id, at).trigger(Trigger.LAMBDA_EVENT);
        Spec fn = t.root(NodeKind.LAMBDA, "order-processor", 0, 300).attr(OtelAttributeNames.FAAS_NAME, "order-processor")
                .attr(OtelAttributeNames.FAAS_INVOCATION_ID, "req-" + id);
        Spec q = sqsPublish(fn, "orders-queue", 20, 10);
        Spec consumer = lambda(q, "order-billing", "req-c-" + id, 60, 200);
        dynamo(consumer, "UpdateItem", "orders", 80, 60).mutation(MutationKind.UPDATE, "orders", key,
                "{\"status\":{\"S\":\"CONFIRMED\"}}", "{\"status\":{\"S\":\"BILLED\"}}", "status", "\"CONFIRMED\"", "\"BILLED\"");
        return t.build();
    }

    static Scenario duplicateConsumption() {
        return new Scenario("duplicacao-de-processamento", "order-billing cobra ORDER-8 duas vezes (reentrega)",
                Stage.EXECUTION, List.of(billing("dup-1", NOW.minusSeconds(40), "ORDER-8")),
                billing("dup-2", NOW, "ORDER-8"), Map.of(), null, null, Set.of("IDEM-002"));
    }

    /**
     * Controle vindo da stack alvo (finance-pix, J3→J12): o consumidor de liquidação faz o MESMO
     * UPDATE parametrizado para transferências DIFERENTES; o Δ inferido do JDBC só conhece o WHERE
     * com placeholders ("transfer_id = ? …") — não é a identidade da entidade. Antes: IDEM-002 falso.
     */
    static Scenario inferredSqlTemplateKey() {
        return new Scenario("controle-sql-inferido-chave-template",
                "Mesmo UPDATE parametrizado (transfer_id = ?) para duas transferências distintas",
                Stage.EXECUTION, List.of(settlement("sq-1", NOW.minusSeconds(40))), settlement("sq-2", NOW),
                Map.of(), null, null, Set.of());
    }

    static Execution settlement(String id, Instant at) {
        TraceBuilder t = TraceBuilder.execution(id, at).trigger(Trigger.LAMBDA_EVENT);
        Spec fn = t.root(NodeKind.LAMBDA, "pix-api", 0, 300).attr(OtelAttributeNames.FAAS_NAME, "pix-api")
                .attr(OtelAttributeNames.FAAS_INVOCATION_ID, "req-" + id);
        Spec q = sqsPublish(fn, "pix-settlement", 20, 10);
        Spec consumer = lambda(q, "pix-settlement", "req-c-" + id, 60, 200);
        consumer.child(NodeKind.SQL, "SQL: UPDATE ledger_entries", 80, 20)
                .inferredMutation(MutationKind.UPDATE, "ledger_entries",
                        "transfer_id = ? AND entry_type = 'HOLD' AND status = 'PENDING' RETURNING account_id, amount");
        return t.build();
    }

    static Scenario guardedDuplicate() {
        Execution first = creation("gd-1", NOW.minusSeconds(30), "ORDER-5", null);
        TraceBuilder t = TraceBuilder.execution("gd-2", NOW).trigger(Trigger.LAMBDA_EVENT);
        Spec fn = t.root(NodeKind.LAMBDA, "idempotent-processor", 0, 60).attr(OtelAttributeNames.FAAS_NAME, "idempotent-processor");
        Spec guard = fn.child(NodeKind.BUSINESS, "IdempotencyGuard", 5, 50);
        dynamo(guard, "PutItem", "idempotency", 10, 30)
                .error("software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException",
                        "The conditional request failed");
        return new Scenario("idempotencia-protegida", "Controle: duplicado recusado pela guarda condicional",
                Stage.EXECUTION, List.of(first), t.build(), Map.of(), null, null, Set.of());
    }

    // ================================================================== histórico

    static Execution simpleFlow(String id, Instant at, long businessMs, long dbMs, String extraTable) {
        TraceBuilder t = TraceBuilder.execution(id, at);
        Spec api = http(null, t, "GET", "/catalog", 0, businessMs + dbMs + 20);
        Spec svc = api.child(NodeKind.BUSINESS, "CatalogService.list", 5, businessMs + dbMs + 10);
        dynamo(svc, "Query", "products", 10, dbMs).mutation(MutationKind.READ_ONLY, "products", "cat=" + id, null, null);
        if (extraTable != null) {
            dynamo(svc, "PutItem", extraTable, 12 + dbMs, 5)
                    .mutation(MutationKind.CREATE, extraTable, "a-" + id, null, "{\"pk\":{\"S\":\"a\"}}");
        }
        return t.build();
    }

    static Scenario regression() {
        List<Execution> priors = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            priors.add(simpleFlow("rg-old-" + i, YESTERDAY.plusSeconds(i * 60L), 380 + (i % 3) * 10, 30, null));
        }
        for (int i = 0; i < 4; i++) {
            priors.add(simpleFlow("rg-new-" + i, NOW.minusSeconds(300 - i * 30L), 850 + (i % 2) * 20, 30, null));
        }
        return new Scenario("regressao-de-performance", "Mediana do fluxo foi de ~420 ms (ontem) para ~890 ms (hoje)",
                Stage.EXECUTION, priors, simpleFlow("rg-target", NOW, 860, 30, null), Map.of(), null, null,
                Set.of("PERF-REG-001"));
    }

    static Scenario outlier() {
        List<Execution> priors = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            priors.add(simpleFlow("ol-" + i, NOW.minusSeconds(900 - i * 30L), 250 + (i % 4) * 5, 30, null));
        }
        return new Scenario("execucao-fora-da-curva", "Uma execução de ~2 s num fluxo de ~300 ms (banco lento)",
                Stage.EXECUTION, priors, simpleFlow("ol-target", NOW, 260, 1700, null), Map.of(), null, null,
                Set.of("PERF-OUT-001"));
    }

    static Scenario shapeChange() {
        List<Execution> priors = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            priors.add(simpleFlow("sh-" + i, NOW.minusSeconds(600 - i * 30L), 200, 30, null));
        }
        return new Scenario("mudanca-de-comportamento", "O fluxo passou a gravar numa tabela nova (audit)",
                Stage.EXECUTION, priors, simpleFlow("sh-target", NOW, 200, 30, "audit"), Map.of(), null, null,
                Set.of("PRED-SHAPE-001"));
    }

    // ================================================================== resiliência

    static Execution partnerCall(String id, String host, boolean timeout, String url) {
        TraceBuilder t = TraceBuilder.execution(id, NOW).status(timeout ? ExecutionStatus.FAILED : ExecutionStatus.COMPLETED);
        Spec api = http(null, t, "POST", "/payments", 0, 900);
        Spec svc = api.child(NodeKind.BUSINESS, "PaymentService.pay", 10, 880)
                .attr(OtelAttributeNames.CODE_NAMESPACE, "com.acme.payment.PaymentService");
        Spec ext = svc.child(NodeKind.HTTP_CLIENT, "POST " + host, 20, 850).attr(OtelAttributeNames.SERVER_ADDRESS, host)
                .attr(OtelAttributeNames.HTTP_METHOD, "POST");
        if (url != null) {
            ext.attr("url.full", url);
        }
        if (timeout) {
            ext.error("java.net.SocketTimeoutException", "Read timed out");
        }
        return t.build();
    }

    static ProjectSnapshot code(boolean protectedClass, boolean retryOnly) {
        Map<String, List<ProjectSnapshot.Marker>> res = new LinkedHashMap<>();
        if (protectedClass) {
            res.put("PaymentService", List.of(new ProjectSnapshot.Marker(retryOnly ? "@Retry" : "@CircuitBreaker",
                    "src/main/java/com/acme/payment/PaymentService.java", 21)));
        }
        return new ProjectSnapshot(List.of("."), Map.of(), null, null, res, List.of(),
                Map.of("PaymentService", new ProjectSnapshot.Location("src/main/java/com/acme/payment/PaymentService.java", 12)),
                NOW);
    }

    static Scenario missingResilience() {
        return new Scenario("ausencia-de-retry-e-circuit-breaker", "Chamada ao parceiro PIX no caminho crítico sem proteção",
                Stage.EXECUTION, List.of(), partnerCall("res-1", "api.parceiro-pix.com", false, null), Map.of(),
                code(false, false), null, Set.of("RES-001"));
    }

    static Scenario resilienceOk() {
        return new Scenario("resiliencia-presente", "Controle: a classe de origem tem @CircuitBreaker",
                Stage.EXECUTION, List.of(), partnerCall("res-2", "api.parceiro-pix.com", false, null), Map.of(),
                code(true, false), null, Set.of());
    }

    static Scenario timeout() {
        return new Scenario("timeout-inadequado", "Parceiro estoura o read timeout (há @Retry, mas o tempo esgota)",
                Stage.EXECUTION, List.of(), partnerCall("to-1", "api.cotacao.com", true, null), Map.of(),
                code(true, true), null, Set.of("RES-002"));
    }

    // ================================================================== segurança

    static Scenario sensitiveLogs() {
        TraceBuilder t = TraceBuilder.execution("sl", NOW).trigger(Trigger.LAMBDA_EVENT);
        t.root(NodeKind.LAMBDA, "order-processor", 0, 50).attr(OtelAttributeNames.FAAS_NAME, "order-processor").attr(OtelAttributeNames.FAAS_INVOCATION_ID, "req-sl");
        Execution e = t.build();
        List<LogEntry> logs = List.of(
                log(e, "INFO", "Pedido criado para cliente [TRACE2LOCAL_REDACTED] valor 10.00"),
                log(e, "DEBUG", "Authorization: Bearer [TRACE2LOCAL_REDACTED]"),
                log(e, "INFO", "processamento concluído"));
        return new Scenario("exposicao-de-dados-sensiveis", "A aplicação loga e-mail e token em texto puro",
                Stage.EXECUTION, List.of(), e, Map.of(e.executionId(), logs), null, null, Set.of("SEC-LOG-001"));
    }

    static Scenario piiPayload() {
        TraceBuilder t = TraceBuilder.execution("pii", NOW);
        http(null, t, "POST", "/customers", 0, 40)
                .payload("{\"customerName\":\"Maria Silva\",\"telefone\":\"83999990000\",\"plan\":\"gold\",\"tableName\":\"x\"}",
                        "{\"id\":\"C-9\"}");
        return new Scenario("dado-pessoal-em-payload", "Nome e telefone trafegam sem redação", Stage.EXECUTION,
                List.of(), t.build(), Map.of(), null, null, Set.of("SEC-PII-001"));
    }

    /**
     * Controle vindo da stack alvo (finance-pix): o DICT devolve o titular JÁ mascarado
     * ("J*** S***", CPF "***.456.789-**") e o serviço grava o nome mascarado no DynamoDB.
     * Antes: SEC-PII-001 acusava "sem redação" só pelo nome do campo.
     */
    static Scenario piiAlreadyMasked() {
        TraceBuilder t = TraceBuilder.execution("pii-mask", NOW);
        // (o payload mascarado vem na resposta do serviço; uma chamada externa aqui dispararia RES-001, outro assunto)
        Spec api = http(null, t, "POST", "/pix/transfers", 0, 60)
                .payload("{\"pixKey\":\"joao@pix.example\",\"amount\":150}",
                        "{\"owner\":{\"name\":\"J*** S***\",\"taxIdNumber\":\"***.456.789-**\"},\"keyType\":\"EMAIL\"}");
        dynamo(api, "PutItem", "pix-transfers", 30, 10).mutation(MutationKind.CREATE, "pix-transfers", "pix-1", null,
                "{\"transferId\":\"pix-1\",\"receiverName\":\"J*** S***\",\"status\":\"ACCEPTED\"}");
        return new Scenario("controle-dado-pessoal-ja-mascarado", "Nome e CPF chegam mascarados do parceiro e são gravados assim",
                Stage.EXECUTION, List.of(), t.build(), Map.of(), null, null, Set.of());
    }

    static Scenario urlSecret() {
        return new Scenario("segredo-em-url", "API key do parceiro na query string", Stage.EXECUTION, List.of(),
                partnerCall("url-1", "api.rates.example", false, "https://api.rates.example/v1/rates?apikey=abc123def"),
                Map.of(), code(true, false), null, Set.of("SEC-URL-001"));
    }

    // ================================================================== acervo

    static Scenario hotspot() {
        List<Execution> corpus = new ArrayList<>();
        String[] routes = {"/orders", "/payments", "/customers", "/catalog"};
        String[] deps = {"orders", "payments", "customers", "products", "audit", "ledger", "outbox"};
        for (int i = 0; i < 12; i++) {
            TraceBuilder t = TraceBuilder.execution("hs-" + i, NOW.minusSeconds(600 - i * 20L));
            Spec api = http(null, t, "POST", routes[i % 4], 0, 300);
            Spec hub = api.child(NodeKind.BUSINESS, "OrderHub.handle", 5, 290);
            for (int d = 0; d < 3; d++) {
                String table = deps[(i + d) % deps.length];
                dynamo(hub, "GetItem", table, 10 + d * 30L, 20).mutation(MutationKind.READ_ONLY, table, "k" + d, null, null);
            }
            corpus.add(t.build());
        }
        return new Scenario("alto-acoplamento", "OrderHub participa de todos os fluxos e fala com 7 tabelas + 4 rotas",
                Stage.CORPUS, corpus, null, Map.of(), null, null, Set.of("ARCH-HOT-001"));
    }

    static Scenario errorTrend() {
        List<Execution> corpus = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            boolean fail = i >= 8 && i % 4 != 0;
            TraceBuilder t = TraceBuilder.execution("et-" + i, NOW.minusSeconds(900 - i * 30L))
                    .status(fail ? ExecutionStatus.FAILED : ExecutionStatus.COMPLETED);
            Spec api = http(null, t, "POST", "/checkout", 0, 100);
            Spec svc = api.child(NodeKind.BUSINESS, "CheckoutService.pay", 5, 90);
            if (fail) {
                svc.error("java.lang.IllegalStateException", "estoque insuficiente");
            }
            corpus.add(t.build());
        }
        return new Scenario("falhas-crescentes", "Checkout passou de 0% para 75% de falhas nas últimas 8 execuções",
                Stage.CORPUS, corpus, null, Map.of(), null, null, Set.of("PRED-ERR-001"));
    }

    static Execution publishOnly(String id, Instant at, boolean withConsumer) {
        TraceBuilder t = TraceBuilder.execution(id, at).trigger(Trigger.LAMBDA_EVENT);
        Spec fn = t.root(NodeKind.LAMBDA, "order-processor", 0, 100).attr(OtelAttributeNames.FAAS_NAME, "order-processor")
                .attr(OtelAttributeNames.FAAS_INVOCATION_ID, "req-" + id);
        Spec q = sqsPublish(fn, "orders-queue", 20, 10);
        if (withConsumer) {
            lambda(q, "order-billing", "req-c-" + id, 60, 50);
        }
        return t.build();
    }

    static Scenario consumerStopped() {
        List<Execution> priors = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            priors.add(publishOnly("cs-" + i, NOW.minusSeconds(600 - i * 30L), true));
        }
        return new Scenario("consumidor-parou", "Seis execuções com consumidor; a sétima publica e ninguém consome",
                Stage.EXECUTION, priors, publishOnly("cs-target", NOW, false), Map.of(), null, null,
                Set.of("PRED-CONS-001"));
    }

    static Scenario firstOrphan() {
        return new Scenario("publicacao-sem-consumidor", "Primeira execução publica sem consumidor observado (JC-3)",
                Stage.EXECUTION, List.of(), publishOnly("orph", NOW, false), Map.of(), null, null,
                Set.of("ASYNC-ORPH-001"));
    }

    static Scenario happyPath() {
        return new Scenario("caminho-feliz", "Controle: fluxo síncrono+assíncrono saudável", Stage.EXECUTION,
                List.of(), orderFlow("happy", NOW, 30), Map.of(), null, null, Set.of());
    }

    // ================================================================== controles difíceis

    static Scenario fewDistinctReads() {
        TraceBuilder t = TraceBuilder.execution("few", NOW);
        Spec api = http(null, t, "GET", "/cart", 0, 120);
        Spec svc = api.child(NodeKind.BUSINESS, "CartService.load", 5, 110);
        for (int i = 0; i < 5; i++) {
            dynamo(svc, "GetItem", "products", 10 + i * 15L, 10).mutation(MutationKind.READ_ONLY, "products", "P-" + i, null, null);
        }
        return new Scenario("controle-5-leituras", "Controle: 5 GetItem distintos (abaixo do limiar de N+1)", Stage.EXECUTION,
                List.of(), t.build(), Map.of(), null, null, Set.of());
    }

    static Scenario batchRead() {
        TraceBuilder t = TraceBuilder.execution("batch", NOW);
        Spec api = http(null, t, "GET", "/orders/report", 0, 80);
        Spec svc = api.child(NodeKind.BUSINESS, "ReportService.build", 5, 70);
        dynamo(svc, "BatchGetItem", "customers", 10, 40).mutation(MutationKind.READ_ONLY, "customers", "C-0..C-119", null, null);
        return new Scenario("controle-batch", "Controle: o mesmo relatório com BatchGetItem (já corrigido)", Stage.EXECUTION,
                List.of(), t.build(), Map.of(), null, null, Set.of());
    }

    static Scenario noisyButStable() {
        List<Execution> priors = new ArrayList<>();
        long[] noise = {300, 410, 260, 380, 330, 290, 450, 310, 350, 270};
        for (int i = 0; i < noise.length; i++) {
            priors.add(simpleFlow("ns-" + i, NOW.minusSeconds(1200 - i * 60L), noise[i], 30, null));
        }
        return new Scenario("controle-ruido", "Controle: fluxo ruidoso (260–450 ms) com execução de 420 ms", Stage.EXECUTION,
                priors, simpleFlow("ns-target", NOW, 390, 30, null), Map.of(), null, null, Set.of());
    }

    static Scenario localstackClient() {
        return new Scenario("controle-localstack", "Controle: cliente HTTP chamando o LocalStack (fronteira local)",
                Stage.EXECUTION, List.of(), partnerCall("ls-1", "localhost", false, "http://localhost:4566/"), Map.of(),
                code(false, false), null, Set.of());
    }

    static Scenario innocentLogs() {
        TraceBuilder t = TraceBuilder.execution("il", NOW).trigger(Trigger.LAMBDA_EVENT);
        t.root(NodeKind.LAMBDA, "order-processor", 0, 50).attr(OtelAttributeNames.FAAS_NAME, "order-processor").attr(OtelAttributeNames.FAAS_INVOCATION_ID, "req-il");
        Execution e = t.build();
        List<LogEntry> logs = List.of(
                log(e, "INFO", "password reset requested for order flow"),
                log(e, "WARN", "token bucket almost empty (rate limit 80%)"),
                log(e, "INFO", "processado em 1727740800000 ms epoch"));
        return new Scenario("controle-logs-inocentes", "Controle: logs com palavras sensíveis mas sem valor sensível",
                Stage.EXECUTION, List.of(), e, Map.of(e.executionId(), logs), null, null, Set.of());
    }

    static Scenario technicalNames() {
        TraceBuilder t = TraceBuilder.execution("tn", NOW);
        http(null, t, "POST", "/deploy", 0, 30)
                .payload("{\"tableName\":\"orders\",\"queueName\":\"q\",\"functionName\":\"f\",\"typeName\":\"x\",\"charge\":\"on\",\"target\":\"prod\"}", null);
        return new Scenario("controle-nomes-tecnicos", "Controle: campos 'tableName/queueName/target/charge' não são dado pessoal",
                Stage.EXECUTION, List.of(), t.build(), Map.of(), null, null, Set.of());
    }

    // ================================================================== projeto

    static Scenario terraformDrift() {
        return new Scenario("divergencia-terraform", "dev/hml/prod divergem em escopo, timeout, flag e há senha literal",
                Stage.PROJECT, List.of(), null, Map.of(), null, root -> {
            write(root, "terraform/envs/dev/terraform.tfvars", """
                    api_url = "https://api-sandbox.parceiro.com/v2"
                    oauth_scope = "orders.read orders.write"
                    timeout_seconds = 30
                    feature_new_checkout_enabled = true
                    table_name = "orders-dev"
                    db_password = "Sup3rS3cret!"
                    """);
            write(root, "terraform/envs/hml/terraform.tfvars", """
                    api_url = "https://api-sandbox.parceiro.com/v2"
                    oauth_scope = "orders.read orders.write"
                    timeout_seconds = 30
                    feature_new_checkout_enabled = true
                    table_name = "orders-hml"
                    db_password = var.db_password
                    """);
            write(root, "terraform/envs/prod/terraform.tfvars", """
                    api_url = "https://api.parceiro.com/v1"
                    oauth_scope = "orders.read"
                    timeout_seconds = 3
                    table_name = "orders-prod"
                    db_password = var.db_password
                    """);
        }, Set.of("IAC-DRIFT-001", "IAC-SEC-001"));
    }

    static Scenario terraformCoherent() {
        return new Scenario("terraform-coerente", "Controle: só nome de ambiente e dimensionamento diferem",
                Stage.PROJECT, List.of(), null, Map.of(), null, root -> {
            for (String env : List.of("dev", "hml", "prod")) {
                write(root, "infra/" + env + ".tfvars", """
                        table_name = "orders-%s"
                        queue_url = "https://sqs.us-east-1.amazonaws.com/000000000000/orders-%s"
                        lambda_memory = %s
                        db_password = var.db_password
                        """.formatted(env, env, env.equals("prod") ? "1024" : "512"));
            }
        }, Set.of());
    }

    static Scenario coverageLow() {
        return new Scenario("quality-gate-quebrado", "Cobertura 86% com gate jacoco:check de 90%", Stage.PROJECT,
                List.of(), null, Map.of(), null, root -> coverage(root, 300, 700, 50, 1450), Set.of("TEST-COV-001"));
    }

    static Scenario coverageOk() {
        return new Scenario("quality-gate-ok", "Controle: cobertura 93% com gate de 90%", Stage.PROJECT, List.of(), null,
                Map.of(), null, root -> coverage(root, 100, 900, 75, 1425), Set.of());
    }

    private static void coverage(Path root, long m1, long c1, long m2, long c2) throws IOException {
        write(root, "pom.xml", """
                <project><build><plugins><plugin><artifactId>jacoco-maven-plugin</artifactId>
                <executions><execution><id>check</id><goals><goal>check</goal></goals><configuration><rules><rule>
                  <element>BUNDLE</element>
                  <limits><limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.90</minimum></limit></limits>
                </rule></rules></configuration></execution></executions></plugin></plugins></build></project>
                """);
        write(root, "target/site/jacoco/jacoco.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="app"><package name="com/acme/payment/service"><class name="X"><counter type="LINE" missed="1" covered="1"/></class>
                <counter type="LINE" missed="%d" covered="%d"/></package>
                <package name="com/acme/order/api"><counter type="LINE" missed="%d" covered="%d"/></package>
                <counter type="LINE" missed="%d" covered="%d"/></report>
                """.formatted(m1, c1, m2, c2, m1 + m2, c1 + c2));
    }

    // ================================================================== util

    private static LogEntry log(Execution e, String level, String message) {
        return new LogEntry(e.startedAt().plusMillis(10), level, "com.acme.App", message, e.traceId(), null,
                null, "/aws/lambda/order-processor", "2026/09/30/[$LATEST]abc", LogEntry.LogSource.APP);
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
