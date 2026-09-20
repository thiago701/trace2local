package tech.neural7.tracevanta.spring;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.Redactor;

/**
 * Aspecto do {@link TraceVanta}: envolve o método anotado num span de negócio.
 * O caminho é AOP declarativo + DI — o mesmo mecanismo do caminho sem agente do
 * OpenTelemetry (ADR-001/§9.1), sem transformação de bytecode em runtime.
 */
@Aspect
public class TraceVantaAspect {

    private final TraceVantaConfig cfg;

    public TraceVantaAspect(TraceVantaConfig cfg) {
        this.cfg = cfg;
    }

    @Around("@annotation(traceVanta)")
    public Object aroundBusinessMethod(ProceedingJoinPoint joinPoint, TraceVanta traceVanta) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String label = traceVanta.value() == null || traceVanta.value().isBlank()
                ? signature.getMethod().getName()
                : traceVanta.value();
        // resolvido a cada chamada: em testes, o SDK ativo pode ser trocado pela extensão;
        // TraceVantaOtel.get() cobre "SDK nosso" e "SDK do dev" (registro próprio — ver javadoc)
        Tracer tracer = tech.neural7.tracevanta.otel.TraceVantaOtel.get().getTracer("tech.neural7.tracevanta:business");
        Span span = tracer.spanBuilder(label).startSpan();
        span.setAttribute(tech.neural7.tracevanta.otel.TraceVantaAttributes.BUSINESS, "true");
        span.setAttribute(tech.neural7.tracevanta.otel.OtelAttributeNames.CODE_NAMESPACE,
                signature.getDeclaringType().getSimpleName());
        span.setAttribute(tech.neural7.tracevanta.otel.OtelAttributeNames.CODE_FUNCTION,
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
