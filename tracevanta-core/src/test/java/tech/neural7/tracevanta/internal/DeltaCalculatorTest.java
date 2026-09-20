package tech.neural7.tracevanta.internal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.model.FieldDelta;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaCalculatorTest {

    @Test
    void computesChangedAddedAndRemovedFields() {
        JsonNode before = JsonSupport.parse("{\"orderId\":\"88291\",\"total\":250.00,\"status\":\"PENDING\",\"note\":\"old\"}");
        JsonNode after = JsonSupport.parse("{\"orderId\":\"88291\",\"total\":251.00,\"status\":\"CONFIRMED\",\"customer\":\"C-1\"}");

        List<FieldDelta> deltas = DeltaCalculator.compute(before, after);
        Map<String, FieldDelta> byPath = deltas.stream().collect(Collectors.toMap(FieldDelta::path, d -> d));

        assertThat(byPath).containsOnlyKeys("total", "status", "note", "customer");
        assertThat(byPath.get("total").before().decimalValue()).isEqualByComparingTo("250.00");
        assertThat(byPath.get("total").after().decimalValue()).isEqualByComparingTo("251.00");
        assertThat(byPath.get("note").after().isNull()).isTrue();      // removido
        assertThat(byPath.get("customer").before().isNull()).isTrue(); // adicionado
        // não mudou ⇒ sem delta
        assertThat(byPath).doesNotContainKey("orderId");
    }

    @Test
    void nestedFieldsProduceDottedPaths() {
        JsonNode before = JsonSupport.parse("{\"customer\":{\"name\":\"Ana\",\"tier\":\"silver\"}}");
        JsonNode after = JsonSupport.parse("{\"customer\":{\"name\":\"Ana\",\"tier\":\"gold\"}}");

        List<FieldDelta> deltas = DeltaCalculator.compute(before, after);

        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).path()).isEqualTo("customer.tier");
    }

    @Test
    void newItemHasEveryFieldAsAdded() {
        JsonNode after = JsonSupport.parse("{\"a\":1,\"b\":\"x\"}");

        List<FieldDelta> deltas = DeltaCalculator.compute(null, after);

        assertThat(deltas).extracting(FieldDelta::path).containsExactlyInAnyOrder("a", "b");
        assertThat(deltas).allSatisfy(d -> assertThat(d.before().isNull()).isTrue());
    }
}
