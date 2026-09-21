package tech.neural7.trace2local.otel;

import io.opentelemetry.sdk.trace.data.SpanData;
import tech.neural7.trace2local.model.NodeKind;

import java.util.Map;
import java.util.Optional;

/**
 * Camada anti-corrupção (ADR-008): a ÚNICA classe que traduz o vocabulário do
 * OpenTelemetry para o do Trace2Local. Nenhum nome de atributo do OTel
 * ({@code db.*}, {@code aws.*}, {@code messaging.*}, {@code rpc.*}, {@code http.*})
 * DEVE aparecer fora deste módulo — regra verificada por teste em CI.
 *
 * <p>Degradação graciosa obrigatória: span não reconhecido vira
 * {@code NodeKind.UNKNOWN} rotulado pelo {@code span.name}; o mapper nunca lança
 * e nunca faz um nó desaparecer. Teste de contrato por versão de semconv suportada.
 */
public interface SemanticMapper {

    Optional<NodeKind> kindOf(SpanData span);

    NodeLabel labelOf(SpanData span);

    Map<String, String> inspectorFieldsOf(SpanData span);

    /**
     * Entradas "cruas" usadas quando o span chega por OTLP (Station — modo
     * Companion) e não há {@code SpanData}: {@code spanKind} é o nome do enum
     * ({@code SERVER}, {@code CLIENT}, {@code PRODUCER}, {@code CONSUMER},
     * {@code INTERNAL}). A implementação padrão degrada para UNKNOWN sem lançar.
     */
    default Optional<NodeKind> kindOf(String name, String spanKind, Map<String, String> attributes) {
        return Optional.empty();
    }

    default NodeLabel labelOf(String name, String spanKind, Map<String, String> attributes) {
        return NodeLabel.of(name);
    }
}
