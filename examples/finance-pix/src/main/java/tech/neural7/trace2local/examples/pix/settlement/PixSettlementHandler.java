package tech.neural7.trace2local.examples.pix.settlement;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.SpanContext;
import tech.neural7.trace2local.examples.pix.domain.LedgerRepository;
import tech.neural7.trace2local.examples.pix.domain.PixEvents;
import tech.neural7.trace2local.examples.pix.domain.TransferRepository;
import tech.neural7.trace2local.examples.pix.infra.Db;
import tech.neural7.trace2local.examples.pix.infra.Json;
import tech.neural7.trace2local.examples.pix.partners.SpiClient;
import tech.neural7.trace2local.lambda.Trace2LocalLambdaHandler;
import tech.neural7.trace2local.lambda.Trace2LocalTraceContext;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static tech.neural7.trace2local.otel.Trace2LocalBusiness.step;

/**
 * {@code pix-settlement}: consome a fila de liquidação (SQS, batch de 1). SPI fora
 * do ar = exceção ⇒ a mensagem volta para a fila e é reentregue (a reentrega aparece
 * como continuação na mesma árvore); após 3 tentativas vai para a DLQ.
 * Reentrega de mensagem já liquidada não debita duas vezes (ledger e estado idempotentes).
 */
public final class PixSettlementHandler extends Trace2LocalLambdaHandler<Map<String, Object>, Map<String, Object>> {

    private final SpiClient spi = new SpiClient();
    private final LedgerRepository ledger = new LedgerRepository();
    private final TransferRepository transfers = new TransferRepository();
    private final PixEvents events = new PixEvents();

    @Override
    protected SpanContext remoteParentOf(Map<String, Object> event, Context ctx) {
        return Trace2LocalTraceContext.fromSqsEvent(event);
    }

    @Override
    protected Baggage baggageOf(Map<String, Object> event, Context ctx) {
        return Trace2LocalTraceContext.baggageFromMessageEvent(event);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> handle(Map<String, Object> event, Context ctx) throws Exception {
        List<Map<String, Object>> records = (List<Map<String, Object>>) event.getOrDefault("Records", List.of());
        for (Map<String, Object> record : records) {
            JsonNode m = Json.parse(String.valueOf(record.get("body")));
            String transferId = m.path("transferId").asText();
            String e2e = m.path("endToEndId").asText();
            System.out.println("pix-settlement liquidando " + transferId + " (tentativa "
                    + ((Map<String, Object>) record.getOrDefault("attributes", Map.of())).getOrDefault("ApproximateReceiveCount", "1") + ")");
            String spiStatus = step("Liquidar no SPI", () -> spi.settle(e2e, m.path("amount").decimalValue(),
                    m.path("payerIspb").asText(), m.path("receiverIspb").asText()));
            if (!"SETTLED".equals(spiStatus)) {
                step("Registrar falha de liquidação", () -> transfers.transition(transferId, "ACCEPTED", "FAILED", Map.of("spiStatus", spiStatus)));
                continue;
            }
            boolean debited = step("Debitar no ledger", () -> {
                try (Connection c = Db.connection()) {
                    boolean done = ledger.settle(c, transferId);
                    c.commit();
                    return done;
                }
            });
            boolean moved = step("Concluir transferência", () -> transfers.transition(transferId, "ACCEPTED", "SETTLED",
                    Map.of("settledAt", Instant.now().toString())));
            if (moved) {
                ObjectNode data = Json.object().put("transferId", transferId).put("endToEndId", e2e)
                        .put("payerAccountId", m.path("payerAccountId").asText()).put("amount", m.path("amount").decimalValue());
                step("Publicar liquidação", () -> {
                    events.publish("PIX_SETTLED", data);
                    return null;
                });
            } else {
                System.out.println("WARN pix-settlement reentrega de " + transferId + " ignorada (já liquidada; débito=" + debited + ")");
            }
        }
        return Map.of("processed", records.size());
    }
}
