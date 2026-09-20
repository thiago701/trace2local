package tech.neural7.tracevanta.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.opentelemetry.api.trace.SpanContext;
import tech.neural7.tracevanta.config.TraceVantaConfig;

/**
 * Base para handlers Lambda instrumentados (SPEC §4.12): o flush síncrono e o
 * span raiz são garantidos pelo {@link TraceVantaLambdaRuntime} — o dev só
 * implementa {@link #handle}.
 *
 * <pre>{@code
 * public class OrderHandler extends TraceVantaLambdaHandler<OrderEvent, OrderResult> {
 *     protected OrderResult handle(OrderEvent input, Context ctx) { ... }
 * }
 * }</pre>
 *
 * <p><b>Trigger com continuidade de trace (SQS/EventBridge):</b> quando o evento
 * carrega o contexto do produtor (ex.: {@code AWSTraceHeader} dos atributos da
 * mensagem SQS), sobrescreva {@link #remoteParentOf} devolvendo o
 * {@link SpanContext} remoto — o span raiz da invocação vira FILHO do produtor
 * e a continuação aparece NA MESMA árvore do Station (correlação §4.11).
 */
public abstract class TraceVantaLambdaHandler<I, O> implements RequestHandler<I, O> {

    private final TraceVantaLambdaRuntime runtime;

    protected TraceVantaLambdaHandler() {
        this(TraceVantaLambda.configFromEnv());
    }

    protected TraceVantaLambdaHandler(TraceVantaConfig cfg) {
        this.runtime = TraceVantaLambdaRuntime.forStation(cfg);
    }

    /** Injeção de runtime para teste. */
    TraceVantaLambdaHandler(TraceVantaLambdaRuntime runtime) {
        this.runtime = runtime;
    }

    /** Parent remoto do span raiz (default: sem parent — nova árvore). */
    protected SpanContext remoteParentOf(I input, Context context) {
        return null;
    }

    @Override
    public final O handleRequest(I input, Context context) {
        try {
            return runtime.around(context, remoteParentOf(input, context), () -> handle(input, context));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected abstract O handle(I input, Context context) throws Exception;
}
