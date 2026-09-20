package tech.neural7.tracevanta.otel;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.concurrent.ThreadFactory;

/**
 * ThreadFactory que captura o {@link Context} do OpenTelemetry no fork e o reabre
 * na thread nova (SPEC §4.11). O {@code Context} do OTel é {@code ThreadLocal} puro
 * e {@code Thread.startVirtualThread} NÃO herda contexto (issue oficial do OTel
 * fechada como <em>not planned</em>) — sem esta fábrica, um ramo executado em
 * virtual thread aparece como órfão com pai ausente.
 */
public final class TraceVantaThreadFactory implements ThreadFactory {

    private final ThreadFactory delegate;
    private final String namePrefix;

    public TraceVantaThreadFactory(String namePrefix) {
        this(Thread.ofVirtual().factory(), namePrefix);
    }

    public TraceVantaThreadFactory(ThreadFactory delegate, String namePrefix) {
        this.delegate = delegate;
        this.namePrefix = namePrefix;
    }

    @Override
    public Thread newThread(Runnable task) {
        Context captured = Context.current();
        return delegate.newThread(() -> {
            Thread current = Thread.currentThread();
            String originalName = current.getName();
            if (namePrefix != null) {
                current.setName(namePrefix + "-" + current.threadId());
            }
            try (Scope ignored = captured.makeCurrent()) {
                task.run();
            } finally {
                current.setName(originalName);
            }
        });
    }

    /** Fábrica de virtual threads com contexto propagado e nome amigável. */
    public static ThreadFactory virtual(String namePrefix) {
        return new TraceVantaThreadFactory(namePrefix);
    }
}
