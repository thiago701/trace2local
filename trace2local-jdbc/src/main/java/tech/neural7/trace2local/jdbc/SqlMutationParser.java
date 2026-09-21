package tech.neural7.trace2local.jdbc;

import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.MutationFidelity;
import tech.neural7.trace2local.model.MutationKind;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse leve de SQL para o delta {@code INFERRED} (SPEC §4.10, nível
 * {@code inferred}): deriva a intenção do comando — INSERT/UPDATE/DELETE +
 * tabela + cláusula WHERE — sem tocar no banco. O resultado é exibido com
 * {@code fidelity=INFERRED}, nunca com aparência de dado observado (I3).
 */
final class SqlMutationParser {

    private static final Pattern INSERT = Pattern.compile(
            "(?is)^\\s*insert\\s+into\\s+([\\w.\"`\\[\\]-]+)");
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*update\\s+([\\w.\"`\\[\\]-]+)\\s+set\\s+(.*?)(?:\\swhere\\s+(.*))?$");
    private static final Pattern DELETE = Pattern.compile(
            "(?is)^\\s*delete\\s+from\\s+([\\w.\"`\\[\\]-]+)(?:\\s+where\\s+(.*))?$");
    private static final Pattern SELECT = Pattern.compile(
            "(?is)^\\s*select\\s.*?\\sfrom\\s+([\\w.\"`\\[\\]-]+)");

    private SqlMutationParser() {}

    static Optional<DataMutation> infer(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.empty();
        }
        String s = sql.trim();
        Matcher insert = INSERT.matcher(s);
        if (insert.find()) {
            return Optional.of(mutation(MutationKind.CREATE, clean(insert.group(1)), null));
        }
        Matcher update = UPDATE.matcher(s);
        if (update.matches()) {
            return Optional.of(mutation(MutationKind.UPDATE, clean(update.group(1)), whereKey(update.group(3))));
        }
        Matcher delete = DELETE.matcher(s);
        if (delete.matches()) {
            return Optional.of(mutation(MutationKind.DELETE, clean(delete.group(1)), whereKey(delete.group(2))));
        }
        return Optional.empty();
    }

    static Optional<String> tableOf(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.empty();
        }
        String s = sql.trim().toLowerCase(Locale.ROOT);
        Matcher insert = INSERT.matcher(s);
        if (insert.find()) return Optional.of(clean(insert.group(1)));
        Matcher update = UPDATE.matcher(s);
        if (update.matches()) return Optional.of(clean(update.group(1)));
        Matcher delete = DELETE.matcher(s);
        if (delete.matches()) return Optional.of(clean(delete.group(1)));
        Matcher select = SELECT.matcher(s);
        if (select.find()) return Optional.of(clean(select.group(1)));
        return Optional.empty();
    }

    static String operationOf(String sql) {
        if (sql == null || sql.isBlank()) {
            return "UNKNOWN";
        }
        String s = sql.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("INSERT")) return "INSERT";
        if (s.startsWith("UPDATE")) return "UPDATE";
        if (s.startsWith("DELETE")) return "DELETE";
        if (s.startsWith("SELECT")) return "SELECT";
        return "UNKNOWN";
    }

    private static DataMutation mutation(MutationKind kind, String table, String key) {
        return new DataMutation(kind, table, key, null, null, List.of(), MutationFidelity.INFERRED);
    }

    private static String whereKey(String where) {
        if (where == null || where.isBlank()) {
            return null;
        }
        return where.length() > 120 ? where.substring(0, 120) + "…" : where;
    }

    private static String clean(String table) {
        return table.replaceAll("[\"`\\[\\]]", "");
    }
}
