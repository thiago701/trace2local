package tech.neural7.tracevanta.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import tech.neural7.tracevanta.config.TraceVantaConfig;

/**
 * Alternativa sem herança (SPEC §4.12): decora um {@link RequestHandler} existente.
 *
 * <pre>{@code
 * RequestHandler&lt;OrderEvent, OrderResult&gt; instrumented =
 *     TraceVantaLambda.instrument(myHandler, TraceVantaConfig.defaults());
 * }</pre>
 */
public final class TraceVantaLambda {

    private TraceVantaLambda() {}

    /**
     * Config do ambiente no modo Lambda: {@code TRACEVANTA_STATION_ENDPOINT} (env)
     * com fallback para a propriedade de sistema {@code tracevanta.station.endpoint}
     * (útil em testes), e {@code TRACEVANTA_STATION_TOKEN} (Bearer opcional do
     * ingest — ADR-007). O endpoint é OBRIGATÓRIO (ADR-002).
     */
    public static TraceVantaConfig configFromEnv() {
        String endpoint = System.getenv("TRACEVANTA_STATION_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = System.getProperty("tracevanta.station.endpoint");
        }
        String token = System.getenv("TRACEVANTA_STATION_TOKEN");
        if (token == null || token.isBlank()) {
            token = System.getProperty("tracevanta.station.token");
        }
        return TraceVantaConfig.builder().stationEndpoint(endpoint).stationToken(token).build();
    }

    public static <I, O> RequestHandler<I, O> instrument(RequestHandler<I, O> delegate, TraceVantaConfig cfg) {
        TraceVantaLambdaRuntime runtime = TraceVantaLambdaRuntime.forStation(cfg);
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
