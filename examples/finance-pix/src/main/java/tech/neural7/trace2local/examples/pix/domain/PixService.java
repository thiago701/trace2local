package tech.neural7.trace2local.examples.pix.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.examples.pix.infra.Db;
import tech.neural7.trace2local.examples.pix.infra.Json;
import tech.neural7.trace2local.examples.pix.partners.AntifraudeClient;
import tech.neural7.trace2local.examples.pix.partners.DictClient;
import tech.neural7.trace2local.examples.pix.partners.KycClient;
import tech.neural7.trace2local.examples.pix.partners.PartnerUnavailableException;

import java.sql.Connection;
import java.sql.SQLException;

import static tech.neural7.trace2local.otel.Trace2LocalBusiness.step;

/**
 * Iniciação de Pix com antifraude — o fluxo que o PO reconhece, passo a passo:
 * validar → idempotência → DICT → KYC/limites → reservar saldo → avaliar risco →
 * (APPROVED) aceitar e enviar para liquidação | (REVIEW) mandar para revisão |
 * (DENIED) recusar e liberar o saldo. Falha técnica de parceiro após a reserva
 * COMPENSA (libera o saldo) e devolve 502 — nenhum dinheiro fica preso.
 */
public final class PixService {

    /** Resposta HTTP (status + corpo JSON). */
    public record Result(int status, ObjectNode body) {}

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final TransferRepository transfers = new TransferRepository();
    private final LedgerRepository ledger = new LedgerRepository();
    private final PixEvents events = new PixEvents();
    private final DictClient dict = new DictClient();
    private final KycClient kyc = new KycClient();
    private final AntifraudeClient antifraude = new AntifraudeClient();

    public Result create(String idempotencyKey, TransferRequest req) throws Exception {
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 64) {
            throw new Problem(400, "INVALID_REQUEST", "cabeçalho Idempotency-Key obrigatório (8 a 64 caracteres)");
        }
        IdempotencyStore.Outcome outcome = step("Garantir idempotência", () -> idempotency.begin(idempotencyKey, req.canonical()));
        switch (outcome) {
            case IdempotencyStore.Outcome.Replay r -> {
                return new Result(r.status(), (ObjectNode) Json.parse(r.body()));
            }
            case IdempotencyStore.Outcome.Conflict c ->
                    throw new Problem(409, "IDEMPOTENCY_CONFLICT", "Idempotency-Key já usada com outro payload");
            case IdempotencyStore.Outcome.InProgress p ->
                    throw new Problem(409, "IDEMPOTENCY_CONFLICT", "requisição com esta Idempotency-Key ainda em processamento");
            case IdempotencyStore.Outcome.New n -> { /* segue */ }
        }
        String transferId = Ids.transferId();
        boolean held = false;
        try {
            DictClient.Receiver receiver = step("Resolver chave Pix (DICT)", () -> dict.lookup(req.pixKey()))
                    .orElseThrow(() -> new Problem(422, "INVALID_KEY", "chave Pix não encontrada no DICT"));
            KycClient.Limits limits = step("Verificar KYC e limites", () -> kyc.limits(req.payerAccountId()));
            if ("BLOCKED".equals(limits.kycStatus())) {
                throw new Problem(422, "KYC_BLOCKED", "pagador com cadastro bloqueado");
            }
            if (limits.availableToday() != null && limits.availableToday().compareTo(req.amount()) < 0) {
                throw new Problem(422, "LIMIT_EXCEEDED", "valor acima do limite disponível hoje");
            }
            LedgerRepository.Account payer = step("Reservar saldo", () -> {
                try (Connection c = Db.connection()) {
                    try {
                        LedgerRepository.Account a = ledger.hold(c, req.payerAccountId(), transferId, req.amount());
                        c.commit();
                        return a;
                    } catch (RuntimeException | SQLException e) {
                        c.rollback();
                        throw e;
                    }
                }
            });
            held = true;
            AntifraudeClient.Score score = step("Avaliar risco (antifraude)",
                    () -> antifraude.score(transferId, req.payerAccountId(), receiver.ispb(), req.amount()));
            String endToEndId = Ids.endToEndId(payer.ispb());
            ObjectNode data = Json.object().put("transferId", transferId).put("endToEndId", endToEndId)
                    .put("payerAccountId", req.payerAccountId()).put("payerIspb", payer.ispb())
                    .put("receiverIspb", receiver.ispb()).put("amount", req.amount());
            Result result = switch (score.decision()) {
                case "APPROVED" -> step("Aceitar e enviar para liquidação", () -> {
                    transfers.create(transferId, "ACCEPTED", req, endToEndId, receiver.ispb(), receiver.name(), "APPROVED");
                    events.requestSettlement(data);
                    return new Result(202, accepted(transferId, "ACCEPTED", endToEndId, receiver));
                });
                case "REVIEW" -> step("Enviar para revisão antifraude", () -> {
                    transfers.create(transferId, "IN_REVIEW", req, endToEndId, receiver.ispb(), receiver.name(), "REVIEW");
                    events.publish("PIX_REVIEW_REQUIRED", data.put("reasons", String.join(",", score.reasons())));
                    return new Result(202, accepted(transferId, "IN_REVIEW", endToEndId, receiver));
                });
                default -> step("Recusar transferência", () -> {
                    releaseHold(transferId);
                    transfers.create(transferId, "REJECTED", req, endToEndId, receiver.ispb(), receiver.name(), "DENIED");
                    events.publish("PIX_REJECTED", data.put("reasons", String.join(",", score.reasons())));
                    return new Result(422, problem("REJECTED_BY_FRAUD", "transferência recusada pelo antifraude", null));
                });
            };
            step("Registrar resposta idempotente", () -> {
                idempotency.complete(idempotencyKey, result.status(), Json.write(result.body()));
                return null;
            });
            return result;
        } catch (Problem p) {
            // recusa de negócio também é resposta idempotente (mesmo pedido → mesma recusa)
            if (held) {
                releaseHold(transferId);
            }
            ObjectNode body = problem(p.code(), p.getMessage(), p.partner());
            idempotency.complete(idempotencyKey, p.status(), Json.write(body));
            throw p;
        } catch (PartnerUnavailableException e) {
            // falha técnica: compensa a reserva e libera a chave para nova tentativa
            if (held) {
                releaseHold(transferId);
            }
            idempotency.abandon(idempotencyKey);
            throw new Problem(502, "PARTNER_UNAVAILABLE", e.getMessage(), e.partner());
        }
    }

    public Result find(String transferId) {
        return transfers.find(transferId).map(t -> new Result(200, t))
                .orElseThrow(() -> new Problem(404, "NOT_FOUND", "transferência não encontrada"));
    }

    private void releaseHold(String transferId) throws Exception {
        step("Liberar saldo reservado", () -> {
            try (Connection c = Db.connection()) {
                boolean released = ledger.release(c, transferId);
                c.commit();
                return released;
            }
        });
    }

    private static ObjectNode accepted(String transferId, String status, String endToEndId, DictClient.Receiver receiver) {
        ObjectNode body = Json.object().put("transferId", transferId).put("status", status).put("endToEndId", endToEndId);
        body.putObject("receiver").put("ispb", receiver.ispb()).put("name", receiver.name());
        return body;
    }

    public static ObjectNode problem(String code, String message, String partner) {
        ObjectNode body = Json.object().put("code", code).put("message", message);
        if (partner != null) {
            body.put("partner", partner);
        }
        return body;
    }
}
