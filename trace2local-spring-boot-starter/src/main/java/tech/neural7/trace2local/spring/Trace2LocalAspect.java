package tech.neural7.trace2local.spring;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Redactor;

/**
 * Aspecto do {@link Trace2Local}: envolve o método anotado num span de negócio.
 * O caminho é AOP declarativo + DI — o mesmo mecanismo do caminho sem agente do
 * OpenTelemetry (ADR-001/§9.1), sem transformação de bytecode em runtime.
 */
@Aspect
public class Trace2LocalAspect {

    private final Trace2LocalConfig cfg;

    public Trace2LocalAspect(Trace2LocalConfig cfg) {
        this.cfg = cfg;
    }

    @Around("@annotation(traceVanta)")
    public Object aroundBusinessMethod(ProceedingJoinPoint joinPoint, Trace2Local traceVanta) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String label = traceVanta.value() == null || traceVanta.value().isBlank()
                ? signature.getMethod().getName()
                : traceVanta.value();
        // resolvido a cada chamada: em testes, o SDK ativo pode ser trocado pela extensão;
        // Trace2LocalOtel.get() cobre "SDK nosso" e "SDK do dev" (registro próprio — ver javadoc)
        Tracer tracer = tech.neural7.trace2local.otel.Trace2LocalOtel.get().getTracer("tech.neural7.trace2local:business");
        Span span = tracer.spanBuilder(label).startSpan();
        span.setAttribute(tech.neural7.trace2local.otel.Trace2LocalAttributes.BUSINESS, "true");
        span.setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.CODE_NAMESPACE,
                signature.getDeclaringType().getSimpleName());
        span.setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.CODE_FUNCTION,
                signature.getMethod().getName());
        try (var scope = span.makeCurrent()) {
            Object result = joinPoint.proceed();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getMessage());
            throw t;
        } finally {
            span.end();
        }
    }
}
