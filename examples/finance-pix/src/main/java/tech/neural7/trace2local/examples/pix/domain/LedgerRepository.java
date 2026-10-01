package tech.neural7.trace2local.examples.pix.domain;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Ledger no Postgres. Saldo disponível = {@code balance - held}; a aceitação RESERVA
 * (HOLD), a liquidação DEBITA, a recusa LIBERA. Tudo idempotente por {@code transfer_id}.
 */
public final class LedgerRepository {

    public record Account(String id, String ispb, BigDecimal balance, BigDecimal held) {
        BigDecimal available() {
            return balance.subtract(held);
        }
    }

    /** Reserva o valor (SELECT ... FOR UPDATE + INSERT HOLD + UPDATE held). */
    public Account hold(Connection c, String accountId, String transferId, BigDecimal amount) throws SQLException {
        Account acc;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, ispb, balance, held FROM accounts WHERE id = ? FOR UPDATE")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Problem(422, "INVALID_REQUEST", "conta pagadora inexistente");
                }
                acc = new Account(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4));
            }
        }
        if (acc.available().compareTo(amount) < 0) {
            throw new Problem(422, "INSUFFICIENT_FUNDS", "saldo disponível insuficiente");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entries (id, account_id, transfer_id, entry_type, amount, status) VALUES (?, ?, ?, 'HOLD', ?, 'PENDING')")) {
            ps.setString(1, "le-" + transferId);
            ps.setString(2, accountId);
            ps.setString(3, transferId);
            ps.setBigDecimal(4, amount);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE accounts SET held = held + ?, updated_at = now() WHERE id = ?")) {
            ps.setBigDecimal(1, amount);
            ps.setString(2, accountId);
            ps.executeUpdate();
        }
        return acc;
    }

    /** Libera a reserva (recusa ou falha antes da liquidação). Idempotente. */
    public boolean release(Connection c, String transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ledger_entries SET status = 'RELEASED', updated_at = now() "
                + "WHERE transfer_id = ? AND entry_type = 'HOLD' AND status = 'PENDING' RETURNING account_id, amount")) {
            ps.setString(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE accounts SET held = held - ?, updated_at = now() WHERE id = ?")) {
                    up.setBigDecimal(1, rs.getBigDecimal(2));
                    up.setString(2, rs.getString(1));
                    up.executeUpdate();
                }
                return true;
            }
        }
    }

    /** Converte a reserva em débito efetivo. Reentrega da mensagem não debita duas vezes. */
    public boolean settle(Connection c, String transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ledger_entries SET status = 'SETTLED', updated_at = now() "
                + "WHERE transfer_id = ? AND entry_type = 'HOLD' AND status = 'PENDING' RETURNING account_id, amount")) {
            ps.setString(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE accounts SET balance = balance - ?, held = held - ?, updated_at = now() WHERE id = ?")) {
                    up.setBigDecimal(1, rs.getBigDecimal(2));
                    up.setBigDecimal(2, rs.getBigDecimal(2));
                    up.setString(3, rs.getString(1));
                    up.executeUpdate();
                }
                return true;
            }
        }
    }
}
