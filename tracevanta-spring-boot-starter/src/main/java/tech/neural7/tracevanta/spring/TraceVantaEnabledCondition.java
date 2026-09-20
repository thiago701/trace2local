package tech.neural7.tracevanta.spring;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condição de habilitação (SPEC §8.4): dev → ligado; fora de dev com
 * {@code tracevanta.enabled=true} explícito → os beans são criados e a falha
 * de boot fica a cargo do {@code @Bean} de configuração; fora de dev sem forçar
 * → autodesabilitação silenciosa (com log).
 */
public class TraceVantaEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (ProductionGuard.isDevProfile(environment)) {
            return true;
        }
        boolean explicitlySet = environment.getProperty("tracevanta.enabled") != null;
        boolean enabled = environment.getProperty("tracevanta.enabled", Boolean.class, true);
        if (!explicitlySet) {
            return false; // fora de dev e sem forçar: autodesabilita
        }
        return enabled; // forçado: deixa o @Bean decidir entre habilitar ou falhar o boot
    }
}
