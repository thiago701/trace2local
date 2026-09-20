package tech.neural7.tracevanta.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.Redactor;
import tech.neural7.tracevanta.internal.SpanEndEvent;
import tech.neural7.tracevanta.internal.SpanStartEvent;
import tech.neural7.tracevanta.internal.TraceVantaEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class TraceVantaSpanProcessorTest {

    private final List<TraceVantaEvent> events = new CopyOnWriteArrayList<>();
    private SdkTracerProvider provider;

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
    }

    private Tracer tracer() {
        TraceVantaConfig cfg = TraceVantaConfig.defaults();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(new TraceVantaSpanProcessor(events::add, cfg))
                .build();
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("test");
    }

    @Test
    void emitsStartAndEndEventsWithParentage() {
        Tracer tracer = tracer();
        Span root = tracer.spanBuilder("root").startSpan();
        try (var scope = root.makeCurrent()) {
            Span child = tracer.spanBuilder("child").startSpan();
            child.end();
        } finally {
            root.end();
        }

        List<SpanStartEvent> starts = events.stream().filter(e -> e instanceof SpanStartEvent).map(e -> (SpanStartEvent) e).toList();
        List<SpanEndEvent> ends = events.stream().filter(e -> e instanceof SpanEndEvent).map(e -> (SpanEndEvent) e).toList();

        assertThat(starts).hasSize(2);
        assertThat(ends).hasSize(2);

        SpanStartEvent rootStart = starts.stream().filter(s -> s.label().equals("root")).findFirst().orElseThrow();
        SpanStartEvent childStart = starts.stream().filter(s -> s.label().equals("child")).findFirst().orElseThrow();
        assertThat(rootStart.parentSpanId()).isNull();
        assertThat(childStart.parentSpanId()).isEqualTo(rootStart.spanId());
        assertThat(childStart.traceId()).isEqualTo(rootStart.traceId());
    }

    @Test
    void capturesExecutionIdAndTriggerFromAttributes() {
        Tracer tracer = tracer();
        Span root = tracer.spanBuilder("dispatch")
                .setAttribute(TraceVantaAttributes.EXECUTION_ID, "TV-88291")
                .setAttribute(TraceVantaAttributes.TRIGGER, TraceVantaAttributes.TRIGGER_UI)
                .startSpan();
        root.end();

        SpanStartEvent start = events.stream().filter(e -> e instanceof SpanStartEvent)
                .map(e -> (SpanStartEvent) e).findFirst().orElseThrow();
        assertThat(start.executionId()).isEqualTo("TV-88291");
        assertThat(start.trigger()).isEqualTo(tech.neural7.tracevanta.model.Trigger.UI_DISPATCH);
    }

    @Test
    void redactsSensitiveAttributesAtTheSource() {
        Tracer tracer = tracer();
        Span span = tracer.spanBuilder("secretive")
                .setAttribute("user.password", "hunter2")
                .setAttribute("apiKey", "sk-123")
                .setAttribute("note", "hello@example.com")
                .startSpan();
        span.end();

        SpanEndEvent end = events.stream().filter(e -> e instanceof SpanEndEvent)
                .map(e -> (SpanEndEvent) e).findFirst().orElseThrow();
        assertThat(end.attributes().get("user.password")).isEqualTo(Redactor.REDACTED);
        assertThat(end.attributes().get("apiKey")).isEqualTo(Redactor.REDACTED);
        assertThat(end.attributes().get("note")).isEqualTo(Redactor.REDACTED);
    }

    @Test
    void capturesErrorInfoFromExceptionEvent() {
        Tracer tracer = tracer();
        Span span = tracer.spanBuilder("failing").startSpan();
        try {
            throw new IllegalStateException("Boom");
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Boom");
        } finally {
            span.end();
        }

        SpanEndEvent end = events.stream().filter(e -> e instanceof SpanEndEvent)
                .map(e -> (SpanEndEvent) e).findFirst().orElseThrow();
        assertThat(end.error()).isTrue();
        assertThat(end.errorInfo().type()).contains("IllegalStateException");
        assertThat(end.errorInfo().message()).isEqualTo("Boom");
    }

    @Test
    void neverThrowsFromProcessorContract() {
        // o contrato do OTel: onStart/onEnd não lançam — nem com sink quebrado
        TraceVantaConfig cfg = TraceVantaConfig.defaults();
        TraceVantaSpanProcessor processor = new TraceVantaSpanProcessor(e -> {
            throw new RuntimeException("listener quebrado");
        }, cfg);
        Tracer tracer = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(processor).build())
                .build().getTracer("test");
        Span span = tracer.spanBuilder("x").startSpan();
        span.end(); // não lança
    }
}
