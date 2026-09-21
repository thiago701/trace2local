package tech.neural7.trace2local.spring;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condição de habilitação (SPEC §8.4): dev → ligado; fora de dev com
 * {@code trace2local.enabled=true} explícito → os beans são criados e a falha
 * de boot fica a cargo do {@code @Bean} de configuração; fora de dev sem forçar
 * → autodesabilitação silenciosa (com log).
 */
public class Trace2LocalEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (ProductionGuard.isDevProfile(environment)) {
            return true;
        }
        boolean explicitlySet = environment.getProperty("trace2local.enabled") != null;
        boolean enabled = environment.getProperty("trace2local.enabled", Boolean.class, true);
        if (!explicitlySet) {
            return false; // fora de dev e sem forçar: autodesabilita
        }
        return enabled; // forçado: deixa o @Bean decidir entre habilitar ou falhar o boot
    }
}
