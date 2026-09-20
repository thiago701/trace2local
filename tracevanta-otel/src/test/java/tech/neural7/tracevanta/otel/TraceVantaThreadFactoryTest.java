package tech.neural7.tracevanta.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceVantaThreadFactoryTest {

    @Test
    void propagatesOtelContextToVirtualThreads() throws InterruptedException {
        // sem a fábrica, Thread.startVirtualThread não herda o Context (SPEC §4.11)
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        try {
            var tracer = OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("test");
            Span parent = tracer.spanBuilder("parent").startSpan();

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> childTraceId = new AtomicReference<>();
            AtomicReference<String> childParentId = new AtomicReference<>();

            try (Scope ignored = Context.current().with(parent).makeCurrent()) {
                Thread thread = TraceVantaThreadFactory.virtual("tv-test").newThread(() -> {
                    Span child = tracer.spanBuilder("child").startSpan();
                    SpanContext ctx = child.getSpanContext();
                    childTraceId.set(ctx.getTraceId());
                    childParentId.set(Context.current() == null ? "no-context" : "has-context");
                    child.end();
                    latch.countDown();
                });
                thread.start();
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(childTraceId.get()).isEqualTo(parent.getSpanContext().getTraceId());
            // o Context viajante contém o span pai (verificável indiretamente pelo traceId)
            assertThat(childParentId.get()).isEqualTo("has-context");
            parent.end();
        } finally {
            provider.close();
        }
    }

    @Test
    void stillWorksWithoutActiveContext() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Thread thread = TraceVantaThreadFactory.virtual("tv-null").newThread(latch::countDown);
        thread.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void delegatesNamePrefix() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> seenName = new AtomicReference<>();
        Thread thread = TraceVantaThreadFactory.virtual("tv-name").newThread(() -> {
            seenName.set(Thread.currentThread().getName());
            latch.countDown();
        });
        thread.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seenName.get()).startsWith("tv-name-");
    }
}
