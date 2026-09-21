package tech.neural7.trace2local.jdbc;

import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.model.MutationFidelity;
import tech.neural7.trace2local.model.MutationKind;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMutationParserTest {

    @Test
    void infersInsertIntent() {
        var mutation = SqlMutationParser.infer("INSERT INTO orders (pk, total) VALUES ('ORDER#1', 250)");
        assertThat(mutation).isPresent();
        assertThat(mutation.get().kind()).isEqualTo(MutationKind.CREATE);
        assertThat(mutation.get().target()).isEqualTo("orders");
        assertThat(mutation.get().fidelity()).isEqualTo(MutationFidelity.INFERRED);
    }

    @Test
    void infersUpdateIntentWithWhereKey() {
        var mutation = SqlMutationParser.infer("UPDATE orders SET total = 251 WHERE pk = 'ORDER#1'");
        assertThat(mutation).isPresent();
        assertThat(mutation.get().kind()).isEqualTo(MutationKind.UPDATE);
        assertThat(mutation.get().target()).isEqualTo("orders");
        assertThat(mutation.get().key()).contains("pk = 'ORDER#1'");
    }

    @Test
    void infersDeleteIntent() {
        var mutation = SqlMutationParser.infer("DELETE FROM orders WHERE pk = 'ORDER#9'");
        assertThat(mutation).isPresent();
        assertThat(mutation.get().kind()).isEqualTo(MutationKind.DELETE);
        assertThat(mutation.get().target()).isEqualTo("orders");
    }

    @Test
    void doesNotInferForSelectOrGarbage() {
        assertThat(SqlMutationParser.infer("SELECT * FROM orders WHERE pk = 'x'")).isEmpty();
        assertThat(SqlMutationParser.infer("ALTER TABLE orders ADD COLUMN x INT")).isEmpty();
        assertThat(SqlMutationParser.infer("")).isEmpty();
        assertThat(SqlMutationParser.infer(null)).isEmpty();
    }

    @Test
    void extractsTableAndOperationForSpanSemantics() {
        assertThat(SqlMutationParser.tableOf("SELECT * FROM orders")).contains("orders");
        assertThat(SqlMutationParser.tableOf("INSERT INTO customers (id) VALUES (1)")).contains("customers");
        assertThat(SqlMutationParser.operationOf("update orders set x=1")).isEqualTo("UPDATE");
        assertThat(SqlMutationParser.operationOf("delete from orders")).isEqualTo("DELETE");
        assertThat(SqlMutationParser.operationOf("select 1")).isEqualTo("SELECT");
    }

    @Test
    void truncatesLongWhereKeys() {
        var mutation = SqlMutationParser.infer("UPDATE orders SET total = 1 WHERE " + "x".repeat(500));
        assertThat(mutation).isPresent();
        assertThat(mutation.get().key()).hasSizeLessThan(130);
    }
}
