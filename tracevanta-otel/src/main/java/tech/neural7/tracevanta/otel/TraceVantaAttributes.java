package tech.neural7.tracevanta.otel;

import tech.neural7.tracevanta.model.Trigger;

/** Atributos de span usados pelo disparo da UI e pela correlação (SPEC §4.9). */
public final class TraceVantaAttributes {

    public static final String EXECUTION_ID = "tv.execution.id";
    public static final String TRIGGER = "tv.trigger";
    /** Marca um span como método de negócio (nó BUSINESS — SPEC §1.5/E5). */
    public static final String BUSINESS = "tv.business";

    public static final String TRIGGER_UI = "ui";
    public static final String TRIGGER_EXTERNAL = "external";
    public static final String TRIGGER_LAMBDA = "lambda_event";
    public static final String TRIGGER_TEST = "test";

    private TraceVantaAttributes() {}

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
