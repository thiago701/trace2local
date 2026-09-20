package tech.neural7.tracevanta.station;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import tech.neural7.tracevanta.internal.SpanEndEvent;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;
import tech.neural7.tracevanta.otel.DefaultSemanticMapper;
import tech.neural7.tracevanta.otel.NodeLabel;
import tech.neural7.tracevanta.otel.SemanticMapper;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.model.NodeKind;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingest OTLP/HTTP padrão em {@code /v1/traces} (ADR-002): qualquer serviço já
 * instrumentado com OTel aponta para o Station sem código do TraceVanta. Um
 * serviço que envie apenas OTLP aparece na árvore SEM delta de dados —
 * degradação prevista, não erro (SPEC §5.3).
 */
public final class OtlpTraceReceiver implements HttpHandler {

    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final TraceVantaPipeline pipeline;
    private final TraceVantaConfig cfg;
    private final SemanticMapper mapper = new DefaultSemanticMapper();

    public OtlpTraceReceiver(TraceVantaPipeline pipeline, TraceVantaConfig cfg) {
        this.pipeline = pipeline;
        this.cfg = cfg;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try (InputStream in = exchange.getRequestBody()) {
            ExportTraceServiceRequest request =
                    ExportTraceServiceRequest.parseFrom(in.readNBytes(MAX_BODY_BYTES + 1));
            request.getResourceSpansList().forEach(resourceSpans ->
                    resourceSpans.getScopeSpansList().forEach(scopeSpans ->
                            scopeSpans.getSpansList().forEach(span -> {
                                try {
                                    ingest(span);
                                } catch (Throwable ignored) {
                                    // span ilegível não derruba o ingest (SPEC §7.3)
                                }
                            })));
            exchange.sendResponseHeaders(200, -1);
        } catch (Throwable t) {
            exchange.sendResponseHeaders(400, -1);
        } finally {
            exchange.close();
        }
    }

    private void ingest(io.opentelemetry.proto.trace.v1.Span span) {
        String traceId = toHex(span.getTraceId().toByteArray());
        String spanId = toHex(span.getSpanId().toByteArray());
        String parentSpanId = span.getParentSpanId().isEmpty()
                ? null : toHex(span.getParentSpanId().toByteArray());
        Map<String, String> attributes = new LinkedHashMap<>();
        // redaction NA ORIGEM (ADR-007/§8.3): OTLP de terceiros também passa pelo Redactor
        span.getAttributesList().forEach(kv -> {
            String key = kv.getKey();
            String value = stringValue(kv.getValue());
            if (cfg.redactionMode() != tech.neural7.tracevanta.config.RedactionMode.OFF
                    && tech.neural7.tracevanta.internal.Redactor.isSensitiveKey(key)) {
                value = tech.neural7.tracevanta.internal.Redactor.REDACTED;
            } else {
                value = tech.neural7.tracevanta.internal.Redactor.redactString(value, cfg.redactionMode());
            }
            attributes.put(key, value);
        });
        String spanKind = span.getKind().name().startsWith("SPAN_KIND_")
                ? span.getKind().name().substring("SPAN_KIND_".length())
                : span.getKind().name();
        NodeKind kind = mapper.kindOf(span.getName(), spanKind, attributes).orElse(NodeKind.UNKNOWN);
        NodeLabel label = mapper.labelOf(span.getName(), spanKind, attributes);
        Instant start = Instant.ofEpochSecond(span.getStartTimeUnixNano() / 1_000_000_000L,
                span.getStartTimeUnixNano() % 1_000_000_000L);
        Instant end = Instant.ofEpochSecond(span.getEndTimeUnixNano() / 1_000_000_000L,
                span.getEndTimeUnixNano() % 1_000_000_000L);
        boolean error = span.getStatus().getCode().name().equals("STATUS_CODE_ERROR");
        tech.neural7.tracevanta.model.ErrorInfo errorInfo = null;
        if (error) {
            errorInfo = new tech.neural7.tracevanta.model.ErrorInfo(
                    "OTLP:" + span.getStatus().getCode(),
                    span.getStatus().getMessage(), null);
        }
        // links do mesmo trace: o assembler reparenta o consumidor sob o produtor (§4.11)
        List<String> linkedSpanIds = span.getLinksList().stream()
                .map(link -> toHex(link.getSpanId().toByteArray()))
                .toList();
        // correlação de execução/trigger via OTLP: o modo companion (Lambda) envia
        // tv.execution.id e tv.trigger como atributos do span raiz (§4.9/§4.12)
        String executionId = attributes.get(tech.neural7.tracevanta.otel.TraceVantaAttributes.EXECUTION_ID);
        tech.neural7.tracevanta.model.Trigger trigger =
                tech.neural7.tracevanta.otel.TraceVantaAttributes.parseTrigger(
                        attributes.get(tech.neural7.tracevanta.otel.TraceVantaAttributes.TRIGGER));
        // OTLP não carrega o canal de mutação: execução entra sem delta (declarado)
        pipeline.buffer().offer(new SpanEndEvent(traceId, spanId, parentSpanId, executionId, trigger,
                kind, label.text(), attributes, start, end, error, errorInfo, null, null, linkedSpanIds, true));
    }

    private static String stringValue(io.opentelemetry.proto.common.v1.AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> String.valueOf(value.getBoolValue());
            case INT_VALUE -> String.valueOf(value.getIntValue());
            case DOUBLE_VALUE -> String.valueOf(value.getDoubleValue());
            case ARRAY_VALUE -> {
                // lista de atributos (ex.: aws.dynamodb.table_names) vira join por
                // vírgula — igual ao caminho SpanData da ponte (consistência)
                var sb = new StringBuilder();
                for (int i = 0; i < value.getArrayValue().getValuesCount(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(stringValue(value.getArrayValue().getValues(i)));
                }
                yield sb.toString();
            }
            case KVLIST_VALUE -> value.getKvlistValue().getValuesList().stream()
                    .map(kv -> kv.getKey() + "=" + stringValue(kv.getValue()))
                    .reduce((a, b) -> a + "," + b).orElse("{}");
            case BYTES_VALUE -> "[bytes:" + value.getBytesValue().size() + "]";
            case VALUE_NOT_SET -> "";
            default -> "";
        };
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] readAll(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return in.readAllBytes();
        }
    }
}
