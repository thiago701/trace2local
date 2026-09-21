package tech.neural7.tracevanta.spring;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/** Correlação de logs portátil: OTel (hex) + Datadog (decimal 64 bits) no MDC. */
class TraceVantaLogsTest {

    @AfterEach
    void cleanMdc() {
        TraceVantaLogs.clearTraceIds();
    }

    @Test
    void injectsBothOtelAndDatadogKeysFromActiveSpan() {
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        try {
            var span = sdk.getTracer("test").spanBuilder("op").startSpan();
            try (var scope = span.makeCurrent()) {
                TraceVantaLogs.injectTraceIds(io.opentelemetry.api.trace.Span.current());

                String traceId = span.getSpanContext().getTraceId();
                String spanId = span.getSpanContext().getSpanId();
                assertThat(MDC.get(TraceVantaLogs.KEY_TRACE_ID)).isEqualTo(traceId);
                assertThat(MDC.get(TraceVantaLogs.KEY_SPAN_ID)).isEqualTo(spanId);
                // Datadog: decimal unsigned de 64 bits (últimos 16 hex)
                assertThat(MDC.get(TraceVantaLogs.KEY_DD_TRACE_ID))
                        .isEqualTo(TraceVantaLogs.toUnsignedDecimal(traceId));
                assertThat(MDC.get(TraceVantaLogs.KEY_DD_SPAN_ID))
                        .isEqualTo(TraceVantaLogs.toUnsignedDecimal(spanId));
            } finally {
                span.end();
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void clearRemovesAllKeys() {
        MDC.put(TraceVantaLogs.KEY_TRACE_ID, "x");
        MDC.put(TraceVantaLogs.KEY_DD_TRACE_ID, "1");
        TraceVantaLogs.clearTraceIds();
        assertThat(MDC.get(TraceVantaLogs.KEY_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceVantaLogs.KEY_SPAN_ID)).isNull();
        assertThat(MDC.get(TraceVantaLogs.KEY_DD_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceVantaLogs.KEY_DD_SPAN_ID)).isNull();
    }

    @Test
    void decimalConversionUsesLast64Bits() {
        String expected = Long.toUnsignedString(Long.parseUnsignedLong("ffffffffffffffff", 16));
        assertThat(TraceVantaLogs.toUnsignedDecimal("aaaaaaaaaaaaaaaaffffffffffffffff"))
                .isEqualTo(expected);
        assertThat(TraceVantaLogs.toUnsignedDecimal("curto")).isEqualTo("0");
        assertThat(TraceVantaLogs.toUnsignedDecimal(null)).isEqualTo("0");
    }
}

