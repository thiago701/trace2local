package tech.neural7.trace2local.spring;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Modo detectado, não configurado (ADR-002, regra 3): presença de
 * {@code AWS_LAMBDA_FUNCTION_NAME} ⇒ Companion; {@code trace2local.station.endpoint}
 * definido ⇒ Companion; caso contrário ⇒ Embedded. Esta condição liga apenas o
 * SERVIDOR embedded — em modo Companion a app não hospeda UI.
 */
public class Trace2LocalEmbeddedModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String lambdaFunction = System.getenv("AWS_LAMBDA_FUNCTION_NAME");
        if (lambdaFunction != null && !lambdaFunction.isBlank()) {
            return false; // dentro de uma Lambda não há processo de longa duração para a UI
        }
        String station = context.getEnvironment().getProperty("trace2local.station.endpoint");
        return station == null || station.isBlank();
    }
}
