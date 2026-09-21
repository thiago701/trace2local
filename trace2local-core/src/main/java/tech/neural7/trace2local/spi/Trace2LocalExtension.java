package tech.neural7.trace2local.spi;

import tech.neural7.trace2local.config.RedactionMode;
import tech.neural7.trace2local.model.DataMutation;

import java.util.List;
import java.util.Optional;

/**
 * Superfície pública de extensão do Trace2Local (SPEC §4.7). Registro por
 * {@code META-INF/services} — ServiceLoader, AOT-safe. Tudo além desta interface,
 * do TVEM que ela expõe, das propriedades {@code trace2local.*} e dos contratos
 * REST/SSE é interno e pode mudar sem aviso (SPEC §11.3).
 */
public interface Trace2LocalExtension {

    /** Enriquece um nó antes de ele ser congelado no TVEM. */
    default void contribute(NodeBuilder node, SpanView span) {}

    /**
     * Captura uma mutação de dados para o nó; a primeira extensão que responder vence.
     * Chamado pelo assembler quando nenhuma mutação chegou pelo Data Mutation Channel.
     */
    default Optional<DataMutation> captureMutation(MutationContext ctx) {
        return Optional.empty();
    }

    /** Política de redaction desta extensão. */
    default RedactionPolicy redactionPolicy() {
        return RedactionPolicy.INHERIT;
    }

    /** Descoberta própria de endpoints (ex.: parse do template.yaml do SAM — SPEC §4.8). */
    default List<EndpointDescriptor> discoverEndpoints() {
        return List.of();
    }

    /** Ordem de aplicação; menor roda primeiro. */
    default int order() {
        return 0;
    }

    /** Seletor de modo de redaction efetivo para o dado que esta extensão publica. */
    default RedactionMode effectiveRedaction(RedactionMode global) {
        return switch (redactionPolicy()) {
            case INHERIT -> global;
            case STRICT -> RedactionMode.STRICT;
            case NEVER -> RedactionMode.OFF;
        };
    }
}
