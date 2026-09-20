package tech.neural7.tracevanta.examples.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tech.neural7.tracevanta.otel.TraceVantaOtel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumidor de DEV-TIME da fila {@code billing-queue} — a peça da JC-3:
 * a mensagem chega pelo fanout SNS→SQS com o {@code AWSTraceHeader} (atributo de
 * sistema — o caminho confiável do ADR-003/PESQUISA); o consumidor CONTINUA o
 * MESMO trace do produtor e o processamento aparece na MESMA árvore.
 *
 * <p>Sem contexto na mensagem (best-effort declarado — SPEC §4.11), o ramo vira
 * uma execução própria: degradação honesta, nunca árvore errada.
 */
@Component
public class BillingConsumer implements AutoCloseable {

    /** Root=1-<8hex>-<24hex>;Parent=<16hex>;Sampled=1 */
    private static final Pattern AWS_TRACE_HEADER = Pattern.compile(
            "Root=1-([0-9a-fA-F]{8})-([0-9a-fA-F]{24});Parent=([0-9a-fA-F]{16})");

    private final SqsClient sqs;
    private final OrderService orderService;
    private final String queueUrl;
    private final Thread worker;
    private volatile boolean running = true;
    private long pollBackoffMs = 500;

    public BillingConsumer(SqsClient sqs, OrderService orderService,
                           @Value("${orders.billing-queue-url:}") String queueUrl) {
        this.sqs = sqs;
        this.orderService = orderService;
        this.queueUrl = queueUrl;
        this.worker = new Thread(this::loop, "tracevanta-demo-billing-consumer");
        this.worker.setDaemon(true);
    }

    @PostConstruct
    void start() {
        if (queueUrl != null && !queueUrl.isBlank()) {
            System.out.println("[BillingConsumer] escutando " + queueUrl);
            worker.start();
        } else {
            System.out.println("[BillingConsumer] sem fila configurada — consumidor desligado");
        }
    }

    private void loop() {
        while (running) {
            try {
                var response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageSystemAttributeNames(MessageSystemAttributeName.AWS_TRACE_HEADER)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1)
                        .build());
                for (Message message : response.messages()) {
                    try {
                        process(message);
                    } finally {
                        sqs.deleteMessage(d -> d.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                    }
                }
            } catch (Throwable t) {
                // fila fora do ar não pode derrubar o poller (dev-time); backoff
                pollBackoffMs = Math.min(5000, pollBackoffMs * 2);
                try {
                    Thread.sleep(pollBackoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void process(Message message) {
        String orderId = orderIdOf(message.body());
        if (orderId == null) {
            return;
        }
        String traceHeader = message.attributes().get(MessageSystemAttributeName.AWS_TRACE_HEADER);
        System.out.println("[BillingConsumer] mensagem orderId=" + orderId + " traceHeader=" + traceHeader);
        SpanContext remote = parseAwsTraceHeader(traceHeader);
        // span MANUAL do receive (o wrap do AwsSdkTelemetry exigiria resolução eager
        // do OTel): parent remoto = span do publish SNS → o nó SQS fica na MESMA
        // árvore, como filho do produtor (SPEC §4.11 / JC-3)
        var tracer = TraceVantaOtel.get().getTracer("tech.neural7.tracevanta:demo-consumer");
        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("Sqs.ReceiveMessage")
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.CONSUMER)
                .setAttribute(tech.neural7.tracevanta.otel.OtelAttributeNames.AWS_SQS_QUEUE, queueUrl)
                .setAttribute(tech.neural7.tracevanta.otel.OtelAttributeNames.MESSAGING_SYSTEM, "aws.sqs")
                .setAttribute(tech.neural7.tracevanta.otel.OtelAttributeNames.MESSAGING_DESTINATION, "billing-queue");
        if (remote != null) {
            // continua o trace do produtor: o @TraceVanta do BillOrder fica FILHO
            // do span do publish SNS, na mesma árvore
            builder.setParent(Context.root().with(Span.wrap(remote)));
        }
        Span receive = builder.startSpan();
        try (Scope ignored = receive.makeCurrent()) {
            orderService.bill(orderId);
        } finally {
            receive.end();
        }
    }

    private static String orderIdOf(String body) {
        try {
            JsonNode json = new ObjectMapper().readTree(body);
            JsonNode message = json.path("Message");
            if (message.isTextual()) {
                // envelope do fanout SNS→SQS: o payload real é a string JSON em "Message"
                JsonNode inner = new ObjectMapper().readTree(message.asText());
                String orderId = inner.path("orderId").asText(null);
                return orderId != null && !orderId.isBlank() ? orderId : null;
            }
            String orderId = json.path("orderId").asText(null);
            return orderId != null && !orderId.isBlank() ? orderId : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Converte o AWSTraceHeader em um SpanContext remoto (traceId de 32 hex é W3C-válido). */
    static SpanContext parseAwsTraceHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher matcher = AWS_TRACE_HEADER.matcher(header);
        if (!matcher.find()) {
            return null;
        }
        String traceId = matcher.group(1) + matcher.group(2);
        String spanId = matcher.group(3);
        boolean sampled = header.contains("Sampled=1");
        return SpanContext.createFromRemoteParent(traceId, spanId,
                sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                TraceState.getDefault());
    }

    @PreDestroy
    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
