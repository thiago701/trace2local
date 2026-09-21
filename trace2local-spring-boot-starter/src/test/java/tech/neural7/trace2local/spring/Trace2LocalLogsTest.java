package tech.neural7.trace2local.spring;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/** Correlação de logs portátil: OTel (hex) + Datadog (decimal 64 bits) no MDC. */
class Trace2LocalLogsTest {

    @AfterEach
    void cleanMdc() {
        Trace2LocalLogs.clearTraceIds();
    }

    @Test
    void injectsBothOtelAndDatadogKeysFromActiveSpan() {
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        try {
            var span = sdk.getTracer("test").spanBuilder("op").startSpan();
            try (var scope = span.makeCurrent()) {
                Trace2LocalLogs.injectTraceIds(io.opentelemetry.api.trace.Span.current());

                String traceId = span.getSpanContext().getTraceId();
                String spanId = span.getSpanContext().getSpanId();
                assertThat(MDC.get(Trace2LocalLogs.KEY_TRACE_ID)).isEqualTo(traceId);
                assertThat(MDC.get(Trace2LocalLogs.KEY_SPAN_ID)).isEqualTo(spanId);
                // Datadog: decimal unsigned de 64 bits (últimos 16 hex)
                assertThat(MDC.get(Trace2LocalLogs.KEY_DD_TRACE_ID))
                        .isEqualTo(Trace2LocalLogs.toUnsignedDecimal(traceId));
                assertThat(MDC.get(Trace2LocalLogs.KEY_DD_SPAN_ID))
                        .isEqualTo(Trace2LocalLogs.toUnsignedDecimal(spanId));
            } finally {
                span.end();
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void clearRemovesAllKeys() {
        MDC.put(Trace2LocalLogs.KEY_TRACE_ID, "x");
        MDC.put(Trace2LocalLogs.KEY_DD_TRACE_ID, "1");
        Trace2LocalLogs.clearTraceIds();
        assertThat(MDC.get(Trace2LocalLogs.KEY_TRACE_ID)).isNull();
        assertThat(MDC.get(Trace2LocalLogs.KEY_SPAN_ID)).isNull();
        assertThat(MDC.get(Trace2LocalLogs.KEY_DD_TRACE_ID)).isNull();
        assertThat(MDC.get(Trace2LocalLogs.KEY_DD_SPAN_ID)).isNull();
    }

    @Test
    void decimalConversionUsesLast64Bits() {
        String expected = Long.toUnsignedString(Long.parseUnsignedLong("ffffffffffffffff", 16));
        assertThat(Trace2LocalLogs.toUnsignedDecimal("aaaaaaaaaaaaaaaaffffffffffffffff"))
                .isEqualTo(expected);
        assertThat(Trace2LocalLogs.toUnsignedDecimal("curto")).isEqualTo("0");
        assertThat(Trace2LocalLogs.toUnsignedDecimal(null)).isEqualTo("0");
    }
}

