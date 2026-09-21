package tech.neural7.trace2local.otel;

import io.opentelemetry.sdk.trace.data.SpanData;
import tech.neural7.trace2local.config.RedactionMode;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Redactor;
import tech.neural7.trace2local.model.ErrorInfo;
import tech.neural7.trace2local.model.NodeKind;
import tech.neural7.trace2local.model.Trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Conversão defensiva SpanData → dados do TVEM, com redaction aplicada NA ORIGEM (ADR-007). */
final class SpanDataSupport {

    private static final int MAX_STACK_CHARS = 3000;

    private SpanDataSupport() {}

    static Map<String, String> attributesOf(SpanData data, RedactionMode mode, int maxValueChars) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            data.getAttributes().forEach((key, value) -> {
                String k = key.getKey();
                String v = String.valueOf(value);
                // redação por chave E por valor, antes de entrar no buffer (SPEC §8.3)
                if (mode != RedactionMode.OFF && Redactor.isSensitiveKey(k)) {
                    v = Redactor.REDACTED;
                } else {
                    v = Redactor.redactString(v, mode);
                }
                if (v.length() > maxValueChars) {
                    v = v.substring(0, maxValueChars) + Redactor.TRUNCATED;
                }
                out.put(k, v);
            });
        } catch (Throwable ignored) {
            // atributo ilegível não pode derrubar a coleta
        }
        return out;
    }

    static ErrorInfo errorOf(SpanData data) {
        try {
            boolean statusError = data.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.ERROR;
            if (!statusError && data.getEvents().isEmpty()) {
                return null;
            }
            String type = null;
            String message = null;
            String stack = null;
            for (var event : data.getEvents()) {
                var eventAttrs = event.getAttributes();
                String eventType = stringOf(eventAttrs, "exception.type");
                if (eventType == null) {
                    continue;
                }
                type = eventType;
                message = stringOf(eventAttrs, "exception.message");
                stack = stringOf(eventAttrs, "exception.stacktrace");
                break;
            }
            if (type == null) {
                if (statusError) {
                    type = "error";
                    message = data.getStatus().getDescription();
                } else {
                    return null;
                }
            }
            if (stack != null && stack.length() > MAX_STACK_CHARS) {
                stack = stack.substring(0, MAX_STACK_CHARS) + Redactor.TRUNCATED;
            }
            return new ErrorInfo(type, message, stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringOf(io.opentelemetry.api.common.Attributes attrs, String key) {
        var value = attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(key));
        return value == null ? null : String.valueOf(value);
    }

    static List<String> linkedSpanIdsOf(SpanData data) {
        try {
            List<String> ids = new ArrayList<>();
            data.getLinks().forEach(link -> {
                var ctx = link.getSpanContext();
                if (ctx.isValid()) {
                    ids.add(ctx.getSpanId());
                }
            });
            return ids;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    static Trigger triggerOf(Map<String, String> attributes) {
        return Trace2LocalAttributes.parseTrigger(attributes.get(Trace2LocalAttributes.TRIGGER));
    }

    static String executionIdOf(Map<String, String> attributes) {
        String id = attributes.get(Trace2LocalAttributes.EXECUTION_ID);
        return (id == null || id.isBlank()) ? null : id;
    }

    /** Decora o mapa de atributos com o kind resolvido — usado pelos eventos de início e fim. */
    static NodeKind kindOf(SpanData data, SemanticMapper mapper) {
        return mapper.kindOf(data).orElse(NodeKind.UNKNOWN);
    }

    static String labelOf(SpanData data, SemanticMapper mapper) {
        return mapper.labelOf(data).text();
    }

    /** Aplica o mapper e monta o mapa de atributos uma única vez por span. */
    static MappedSpan map(SpanData data, SemanticMapper mapper, Trace2LocalConfig cfg) {
        Map<String, String> attributes = attributesOf(data, cfg.redactionMode(),
                Math.max(1024, cfg.payloadMaxBytes()));
        return new MappedSpan(kindOf(data, mapper), labelOf(data, mapper), attributes,
                errorOf(data), linkedSpanIdsOf(data), triggerOf(attributes), executionIdOf(attributes));
    }

    record MappedSpan(NodeKind kind, String label, Map<String, String> attributes,
                       ErrorInfo error, List<String> linkedSpanIds, Trigger trigger, String executionId) {}

    static Function<SpanData, MappedSpan> mapper(SemanticMapper semanticMapper, Trace2LocalConfig cfg) {
        return data -> map(data, semanticMapper, cfg);
    }
}
