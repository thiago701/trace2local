package tech.neural7.trace2local.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.util.concurrent.Callable;

/**
 * Passos de NEGÓCIO na árvore sem Spring/AOP (Lambda, CLI, workers) — o equivalente
 * programático da anotação do starter. Cada passo vira um nó {@code BUSINESS} com o
 * nome que o PO reconhece ("Reservar saldo", "Avaliar risco"), agrupando as chamadas
 * técnicas feitas dentro dele.
 *
 * <pre>{@code
 * Decision d = Trace2LocalBusiness.step("Avaliar risco", () -> antifraude.score(t));
 * }</pre>
 *
 * Exceção marca o nó em vermelho (com o tipo e a mensagem) e é relançada intacta.
 */
public final class Trace2LocalBusiness {

    private static final String TRACER = "tech.neural7.trace2local:business";

    private Trace2LocalBusiness() {}

    public static <T> T step(String name, Callable<T> body) throws Exception {
        Span span = Trace2LocalOtel.get().getTracer(TRACER).spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(Trace2LocalAttributes.BUSINESS, "true")
                .startSpan();
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .filter(f -> f.getDeclaringClass() != Trace2LocalBusiness.class).findFirst())
                .ifPresent(f -> {
                    span.setAttribute(OtelAttributeNames.CODE_NAMESPACE, f.getClassName());
                    span.setAttribute(OtelAttributeNames.CODE_FUNCTION, f.getMethodName());
                });
        try (Scope ignored = span.makeCurrent()) {
            return body.call();
        } catch (Exception | Error e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            throw e;
        } finally {
            span.end();
        }
    }

    /** Variante sem retorno. */
    public static void run(String name, ThrowingRunnable body) throws Exception {
        step(name, () -> {
            body.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
