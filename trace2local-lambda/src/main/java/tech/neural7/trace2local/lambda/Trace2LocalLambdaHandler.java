package tech.neural7.trace2local.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.opentelemetry.api.trace.SpanContext;
import tech.neural7.trace2local.config.Trace2LocalConfig;

/**
 * Base para handlers Lambda instrumentados (SPEC §4.12): o flush síncrono e o
 * span raiz são garantidos pelo {@link Trace2LocalLambdaRuntime} — o dev só
 * implementa {@link #handle}.
 *
 * <pre>{@code
 * public class OrderHandler extends Trace2LocalLambdaHandler<OrderEvent, OrderResult> {
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
public abstract class Trace2LocalLambdaHandler<I, O> implements RequestHandler<I, O> {

    private final Trace2LocalLambdaRuntime runtime;

    protected Trace2LocalLambdaHandler() {
        this(Trace2LocalLambda.configFromEnv());
    }

    protected Trace2LocalLambdaHandler(Trace2LocalConfig cfg) {
        this.runtime = Trace2LocalLambdaRuntime.forStation(cfg);
    }

    /** Injeção de runtime para teste. */
    Trace2LocalLambdaHandler(Trace2LocalLambdaRuntime runtime) {
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
