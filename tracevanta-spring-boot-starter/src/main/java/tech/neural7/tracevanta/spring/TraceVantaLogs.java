package tech.neural7.tracevanta.spring;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

/**
 * Correlação de LOGS portátil entre padrões (Datadog + OpenTelemetry):
 * injeta no SLF4J MDC as chaves dos DOIS ecossistemas a partir do span ativo,
 * para que os logs correlacionem no trace local e em qualquer pipeline
 * (coletor OTel/Filelog lê {@code trace_id}/{@code span_id}; o Datadog lê
 * {@code dd.trace_id}/{@code dd.span_id}).
 *
 * <ul>
 *   <li>{@code trace_id}/{@code span_id} — hex de 128/64 bits (padrão OTel);</li>
 *   <li>{@code dd.trace_id}/{@code dd.span_id} — decimal unsigned de 64 bits
 *       (o que o backend do Datadog espera na correlação de logs).</li>
 * </ul>
 *
 * O {@link TraceVantaWebFilter} injeta por requisição automaticamente; outros
 * contextos (consumidores de fila, jobs) podem chamar
 * {@link #injectTraceIds(Span)}/{@link #clearTraceIds()} manualmente.
 */
public final class TraceVantaLogs {

    /** Chaves OTel (OpenTelemetry log data model / coletor Filelog). */
    public static final String KEY_TRACE_ID = "trace_id";
    public static final String KEY_SPAN_ID = "span_id";
    /** Chaves Datadog (correlação de logs padrão dd.). */
    public static final String KEY_DD_TRACE_ID = "dd.trace_id";
    public static final String KEY_DD_SPAN_ID = "dd.span_id";

    private TraceVantaLogs() {}

    /** Injeta as 4 chaves no MDC a partir do span (no-op se o span não for válido). */
    public static void injectTraceIds(Span span) {
        if (span == null || !span.getSpanContext().isValid()) {
            clearTraceIds();
            return;
        }
        String traceId = span.getSpanContext().getTraceId();
        String spanId = span.getSpanContext().getSpanId();
        MDC.put(KEY_TRACE_ID, traceId);
        MDC.put(KEY_SPAN_ID, spanId);
        // Datadog: 64 bits decimais (últimos 16 hex do traceId OTel de 128 bits)
        MDC.put(KEY_DD_TRACE_ID, toUnsignedDecimal(traceId));
        MDC.put(KEY_DD_SPAN_ID, toUnsignedDecimal(spanId));
    }

    public static void clearTraceIds() {
        MDC.remove(KEY_TRACE_ID);
        MDC.remove(KEY_SPAN_ID);
        MDC.remove(KEY_DD_TRACE_ID);
        MDC.remove(KEY_DD_SPAN_ID);
    }

    /** Últimos 16 hex (64 bits) do id → decimal unsigned, ou "0" se inválido. */
    static String toUnsignedDecimal(String hexId) {
        if (hexId == null || hexId.length() < 16) {
            return "0";
        }
        String last64 = hexId.substring(hexId.length() - 16);
        try {
            // String.valueOf(long) imprime COM SINAL — o padrão de bits de 64 bits
            // acima de Long.MAX_VALUE vira "-1"; toUnsignedString é o correto
            return Long.toUnsignedString(Long.parseUnsignedLong(last64, 16));
        } catch (NumberFormatException invalid) {
            return "0";
        }
    }
}
