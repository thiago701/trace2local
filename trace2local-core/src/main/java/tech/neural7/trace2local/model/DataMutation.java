package tech.neural7.trace2local.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Delta de dados de uma mutação de estado (SPEC §4.10). {@code before}/{@code after}
 * são {@code null} quando não existem (item novo / item removido), e {@link #fidelity()}
 * declara a origem do dado — nunca presumida.
 */
public record DataMutation(
        MutationKind kind,
        String target,
        String key,
        JsonNode before,
        JsonNode after,
        List<FieldDelta> deltas,
        MutationFidelity fidelity) {

    /** Mutação sem delta disponível, com fidelidade declarada {@code UNAVAILABLE}. */
    public static DataMutation unavailable(String target, String key) {
        return new DataMutation(MutationKind.READ_ONLY, target, key, null, null, List.of(), MutationFidelity.UNAVAILABLE);
    }
}
