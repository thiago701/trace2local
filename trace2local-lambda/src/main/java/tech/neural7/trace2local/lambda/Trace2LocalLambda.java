package tech.neural7.trace2local.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import tech.neural7.trace2local.config.Trace2LocalConfig;

/**
 * Alternativa sem herança (SPEC §4.12): decora um {@link RequestHandler} existente.
 *
 * <pre>{@code
 * RequestHandler&lt;OrderEvent, OrderResult&gt; instrumented =
 *     Trace2LocalLambda.instrument(myHandler, Trace2LocalConfig.defaults());
 * }</pre>
 */
public final class Trace2LocalLambda {

    private Trace2LocalLambda() {}

    /**
     * Config do ambiente no modo Lambda: {@code TRACE2LOCAL_STATION_ENDPOINT} (env)
     * com fallback para a propriedade de sistema {@code trace2local.station.endpoint}
     * (útil em testes), e {@code TRACE2LOCAL_STATION_TOKEN} (Bearer opcional do
     * ingest — ADR-007). O endpoint é OBRIGATÓRIO (ADR-002).
     */
    public static Trace2LocalConfig configFromEnv() {
        String endpoint = System.getenv("TRACE2LOCAL_STATION_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = System.getProperty("trace2local.station.endpoint");
        }
        String token = System.getenv("TRACE2LOCAL_STATION_TOKEN");
        if (token == null || token.isBlank()) {
            token = System.getProperty("trace2local.station.token");
        }
        return Trace2LocalConfig.builder().stationEndpoint(endpoint).stationToken(token).build();
    }

    public static <I, O> RequestHandler<I, O> instrument(RequestHandler<I, O> delegate, Trace2LocalConfig cfg) {
        Trace2LocalLambdaRuntime runtime = Trace2LocalLambdaRuntime.forStation(cfg);
        return (input, context) -> {
            try {
                return runtime.around(context, () -> delegate.handleRequest(input, context));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
