package tech.neural7.trace2local.otel;

import tech.neural7.trace2local.model.Trigger;

/** Atributos de span usados pelo disparo da UI e pela correlação (SPEC §4.9). */
public final class Trace2LocalAttributes {

    public static final String EXECUTION_ID = "t2l.execution.id";
    public static final String TRIGGER = "t2l.trigger";
    /** Marca um span como método de negócio (nó BUSINESS — SPEC §1.5/E5). */
    public static final String BUSINESS = "t2l.business";
    /** Payload da requisição/resposta (já redigido e truncado) — vira o {@code Payload} do nó. */
    public static final String PAYLOAD_REQUEST = "t2l.payload.request";
    public static final String PAYLOAD_RESPONSE = "t2l.payload.response";
    /**
     * A resposta veio de um mock do Trace2Local (ADR-016): {@code binding=…; stub=…; variation=…}.
     * A árvore marca o nó como SIMULADO — nunca se passa por resposta real.
     */
    public static final String MOCK = "t2l.mock";
    /** A chamada foi desviada para o mock pelo roteamento do cliente (o host original fica em server.address). */
    public static final String MOCK_ROUTED = "t2l.mock.routed";
    /** Cabeçalho de resposta com que os mocks do Trace2Local se identificam. */
    public static final String MOCK_HEADER = "X-Trace2Local-Mock";

    public static final String TRIGGER_UI = "ui";
    public static final String TRIGGER_EXTERNAL = "external";
    public static final String TRIGGER_LAMBDA = "lambda_event";
    public static final String TRIGGER_TEST = "test";

    private Trace2LocalAttributes() {}

    public static Trigger parseTrigger(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case TRIGGER_UI -> Trigger.UI_DISPATCH;
            case TRIGGER_LAMBDA -> Trigger.LAMBDA_EVENT;
            case TRIGGER_TEST -> Trigger.TEST;
            default -> Trigger.EXTERNAL;
        };
    }
}
