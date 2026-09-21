package tech.neural7.trace2local.spring;

import org.springframework.core.env.Environment;

/**
 * Guarda de produção em três camadas (SPEC §8.4 / ADR-007): a forma mais
 * provável de incidente com esta lib é ela subir junto com a aplicação.
 *
 * <ol>
 *   <li>README recomenda escopo {@code provided} ou perfil Maven {@code dev};</li>
 *   <li>fora de perfil de desenvolvimento o starter se autodesabilita;</li>
 *   <li>se {@code trace2local.enabled=true} for forçado fora de dev sem
 *       {@code trace2local.i-know-what-im-doing=true}, o boot FALHA com
 *       mensagem explícita.</li>
 * </ol>
 */
public final class ProductionGuard {

    public static final String[] DEV_PROFILES = {"dev", "development", "local", "localstack"};

    private ProductionGuard() {}

    public static boolean isDevProfile(Environment environment) {
        if (environment == null) {
            return true;
        }
        for (String profile : environment.getActiveProfiles()) {
            for (String dev : DEV_PROFILES) {
                if (dev.equalsIgnoreCase(profile)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Devolve o veredito para o bean de autoconfiguração.
     *
     * @return null = desabilitar silenciosamente; true = habilitar;
     * lança {@link IllegalStateException} quando o dev força o enabled fora de dev.
     */
    public static Boolean decide(Environment environment, boolean enabledExplicitlySet, boolean enabled, boolean iKnow) {
        if (isDevProfile(environment)) {
            return true;
        }
        // fora de dev
        if (enabledExplicitlySet && enabled) {
            if (iKnow) {
                return true;
            }
            throw new IllegalStateException(
                    """
                    Trace2Local é uma ferramenta de DEV-TIME e NÃO DEVE subir em produção (SPEC §8.4).
                    trace2local.enabled=true foi forçado fora de um perfil de desenvolvimento.
                    Se você entende o risco, defina trace2local.i-know-what-im-doing=true —
                    ou remova a dependência/use <scope>provided</scope> no artefato de produção.
                    """);
        }
        // não dev, sem forçar: autodesabilita
        return null;
    }
}
