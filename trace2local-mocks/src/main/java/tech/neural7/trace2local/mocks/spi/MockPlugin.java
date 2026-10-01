package tech.neural7.trace2local.mocks.spi;

import tech.neural7.trace2local.mocks.config.ConfigDef;

/**
 * Contrato comum dos plugins do Mock Connect (ADR-016) — o equivalente ao
 * {@code Connector}/{@code Transformation}/{@code Predicate} do Kafka Connect.
 *
 * <p>Descoberta: {@link java.util.ServiceLoader} sobre
 * {@code META-INF/services/tech.neural7.trace2local.mocks.spi.MockPlugin}, no
 * classpath do Station e em cada JAR de {@code TRACE2LOCAL_MOCKS_PLUGIN_PATH}
 * (um classloader isolado por JAR, como o {@code plugin.path} do Connect).
 *
 * <p>Regras para autores de plugin:
 * <ul>
 *   <li>plugins são <b>sem estado</b> — o estado vive nas instâncias configuradas
 *       ({@code configure(...)}) e no binding;</li>
 *   <li>toda chave de configuração é declarada em {@link #config()} (validação,
 *       documentação e UI saem daí);</li>
 *   <li>nunca registrar valores {@code PASSWORD}; nunca abrir rede fora do que a
 *       configuração declara.</li>
 * </ul>
 */
public interface MockPlugin {

    /** Identificador usado no config ({@code source=openapi}, {@code transforms.x.type=set-field}). */
    String name();

    /** Versão do plugin (exibida em {@code GET /api/mocks/plugins}). */
    default String version() {
        return "1.0.0";
    }

    PluginType type();

    /** Uma frase: o que o plugin faz e quando usar. */
    String description();

    ConfigDef config();
}
