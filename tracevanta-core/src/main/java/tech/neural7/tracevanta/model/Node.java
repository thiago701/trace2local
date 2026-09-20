package tech.neural7.tracevanta.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Nó da árvore do TVEM — "algo que aconteceu com a execução" (SPEC §4.6).
 * {@code nodeId} é o {@code spanId} quando a origem é o OpenTelemetry.
 *
 * <p>Invariantes do modelo (verificadas por teste de propriedade — SPEC §4.6):
 * <ul>
 *   <li>I1 — toda execução é uma árvore, sempre: órfãos são reparentados, nunca descartados;</li>
 *   <li>I2 — {@code selfTime} nunca é negativo;</li>
 *   <li>I3 — fidelidade do delta é declarada em {@link DataMutation#fidelity()}.</li>
 * </ul>
 *
 * @param totalTime pode ser {@code null} em nós parciais transmitidos pelo SSE ainda abertos
 *                 (evento {@code node.upserted}).
 */
public record Node(
        String nodeId,
        String parentId,
        NodeKind kind,
        String label,
        NodeStatus status,
        Instant startedAt,
        Duration selfTime,
        Duration totalTime,
        Map<String, String> attributes,
        Payload payload,
        DataMutation mutation,
        ErrorInfo error,
        List<Node> children) {

    public static final Duration ZERO = Duration.ZERO;
}
