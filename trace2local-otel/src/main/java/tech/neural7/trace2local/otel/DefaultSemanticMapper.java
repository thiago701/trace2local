package tech.neural7.trace2local.otel;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import tech.neural7.trace2local.model.NodeKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static tech.neural7.trace2local.otel.OtelAttributeNames.AWS_DYNAMO_TABLES;
import static tech.neural7.trace2local.otel.OtelAttributeNames.AWS_SNS_TOPIC;
import static tech.neural7.trace2local.otel.OtelAttributeNames.AWS_SQS_QUEUE;
import static tech.neural7.trace2local.otel.OtelAttributeNames.DB_COLLECTION;
import static tech.neural7.trace2local.otel.OtelAttributeNames.DB_NAMESPACE;
import static tech.neural7.trace2local.otel.OtelAttributeNames.DB_OPERATION;
import static tech.neural7.trace2local.otel.OtelAttributeNames.DB_QUERY_TEXT;
import static tech.neural7.trace2local.otel.OtelAttributeNames.DB_SYSTEM;
import static tech.neural7.trace2local.otel.OtelAttributeNames.FAAS_INVOCATION_ID;
import static tech.neural7.trace2local.otel.OtelAttributeNames.FAAS_NAME;
import static tech.neural7.trace2local.otel.OtelAttributeNames.HTTP_METHOD;
import static tech.neural7.trace2local.otel.OtelAttributeNames.HTTP_ROUTE;
import static tech.neural7.trace2local.otel.OtelAttributeNames.HTTP_STATUS;
import static tech.neural7.trace2local.otel.OtelAttributeNames.MESSAGING_DESTINATION;
import static tech.neural7.trace2local.otel.OtelAttributeNames.MESSAGING_OPERATION;
import static tech.neural7.trace2local.otel.OtelAttributeNames.MESSAGING_SYSTEM;
import static tech.neural7.trace2local.otel.OtelAttributeNames.RPC_METHOD;
import static tech.neural7.trace2local.otel.OtelAttributeNames.RPC_SERVICE;
import static tech.neural7.trace2local.otel.OtelAttributeNames.RPC_SYSTEM;

/**
 * Implementação padrão do {@link SemanticMapper} (ADR-008). As convenções de
 * mensageria e AWS estão em status <em>Development</em> no semconv — por isso
 * existe esta classe: um bump do OTel que quebre o mapeamento falha o teste de
 * contrato, não a experiência do dev.
 */
public final class DefaultSemanticMapper implements SemanticMapper {

    private static final String RPC_SYSTEM_AWS = "aws-api";

    @Override
    public Optional<NodeKind> kindOf(SpanData span) {
        try {
            if (span == null) {
                return Optional.empty();
            }
            Map<String, Object> attrs = attributeMap(span);
            if (attrs.containsKey(tech.neural7.trace2local.otel.Trace2LocalAttributes.BUSINESS)) {
                return Optional.of(NodeKind.BUSINESS);
            }
            String rpcSystem = str(attrs, RPC_SYSTEM);
            String rpcService = str(attrs, RPC_SERVICE);
            if (RPC_SYSTEM_AWS.equals(rpcSystem)) {
                return switch (rpcService == null ? "" : rpcService) {
                    case "DynamoDb" -> Optional.of(NodeKind.DYNAMODB);
                    case "Sns" -> Optional.of(NodeKind.SNS);
                    case "Sqs" -> Optional.of(NodeKind.SQS);
                    default -> Optional.empty();
                };
            }
            if (attrs.containsKey(DB_SYSTEM)) {
                return Optional.of(NodeKind.SQL);
            }
            String messaging = str(attrs, MESSAGING_SYSTEM);
            if (messaging != null) {
                if (messaging.contains("sns")) {
                    return Optional.of(NodeKind.SNS);
                }
                if (messaging.contains("sqs")) {
                    return Optional.of(NodeKind.SQS);
                }
            }
            if (attrs.containsKey(FAAS_NAME) || attrs.containsKey(FAAS_INVOCATION_ID)) {
                return Optional.of(NodeKind.LAMBDA);
            }
            if (span.getKind() == SpanKind.SERVER) {
                return Optional.of(NodeKind.HTTP_SERVER);
            }
            if (span.getKind() == SpanKind.CLIENT) {
                return Optional.of(NodeKind.HTTP_CLIENT);
            }
            return Optional.empty();
        } catch (Throwable t) {
            return Optional.empty(); // degradação graciosa: nunca lança (ADR-008)
        }
    }

    @Override
    public NodeLabel labelOf(SpanData span) {
        try {
            if (span == null) {
                return NodeLabel.of("?");
            }
            Map<String, Object> attrs = attributeMap(span);
            Optional<NodeKind> kind = kindOf(span);
            if (kind.isEmpty()) {
                return NodeLabel.of(span.getName());
            }
            return switch (kind.get()) {
                case DYNAMODB -> NodeLabel.of("DynamoDB: " + firstTable(attrs));
                case SNS -> NodeLabel.of("SNS: " + topicName(attrs));
                case SQS -> NodeLabel.of("SQS: " + queueName(attrs));
                case SQL -> NodeLabel.of("SQL: " + sqlTarget(attrs));
                case HTTP_SERVER, HTTP_CLIENT -> {
                    String method = method(attrs);
                    String route = str(attrs, HTTP_ROUTE);
                    String base = route != null && !route.isBlank() ? route : span.getName();
                    yield NodeLabel.of(method == null || method.isBlank() ? base : method + " " + base);
                }
                case LAMBDA -> NodeLabel.of(span.getName());
                default -> NodeLabel.of(span.getName());
            };
        } catch (Throwable t) {
            return NodeLabel.of(span == null ? "?" : span.getName());
        }
    }

    @Override
    public Map<String, String> inspectorFieldsOf(SpanData span) {
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            if (span == null) {
                return fields;
            }
            Map<String, Object> attrs = attributeMap(span);
            put(fields, "operation", rpcOrDbOperation(attrs));
            put(fields, "table", str(attrs, AWS_DYNAMO_TABLES));
            put(fields, "topic", topicName(attrs));
            put(fields, "queue", queueName(attrs));
            put(fields, "db.system", str(attrs, DB_SYSTEM));
            put(fields, "db.operation", str(attrs, DB_OPERATION));
            put(fields, "db.collection", str(attrs, DB_COLLECTION));
            put(fields, "db.query", str(attrs, DB_QUERY_TEXT));
            put(fields, "http.method", str(attrs, HTTP_METHOD));
            put(fields, "http.route", str(attrs, HTTP_ROUTE));
            put(fields, "http.status", str(attrs, HTTP_STATUS));
            put(fields, "faas.name", str(attrs, FAAS_NAME));
            put(fields, "faas.invocation_id", str(attrs, FAAS_INVOCATION_ID));
            put(fields, "messaging.operation", str(attrs, MESSAGING_OPERATION));
            put(fields, "messaging.destination", str(attrs, MESSAGING_DESTINATION));
            put(fields, "span.name", span.getName());
        } catch (Throwable t) {
            // nunca lança
        }
        return fields;
    }

    @Override
    public Optional<NodeKind> kindOf(String name, String spanKind, Map<String, String> attributes) {
        try {
            Map<String, Object> attrs = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
            return kindOfRaw(attrs, spanKind);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public NodeLabel labelOf(String name, String spanKind, Map<String, String> attributes) {
        try {
            Map<String, Object> attrs = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
            Optional<NodeKind> kind = kindOfRaw(attrs, spanKind);
            if (kind.isEmpty()) {
                return NodeLabel.of(name);
            }
            return labelFor(kind.get(), attrs, name);
        } catch (Throwable t) {
            return NodeLabel.of(name);
        }
    }

    private Optional<NodeKind> kindOfRaw(Map<String, Object> attrs, String spanKind) {
        if (attrs.containsKey(tech.neural7.trace2local.otel.Trace2LocalAttributes.BUSINESS)) {
            return Optional.of(NodeKind.BUSINESS);
        }
        String rpcSystem = str(attrs, RPC_SYSTEM);
        String rpcService = str(attrs, RPC_SERVICE);
        if (RPC_SYSTEM_AWS.equals(rpcSystem)) {
            return switch (rpcService == null ? "" : rpcService) {
                case "DynamoDb" -> Optional.of(NodeKind.DYNAMODB);
                case "Sns" -> Optional.of(NodeKind.SNS);
                case "Sqs" -> Optional.of(NodeKind.SQS);
                default -> Optional.empty();
            };
        }
        if (attrs.containsKey(DB_SYSTEM)) {
            return Optional.of(NodeKind.SQL);
        }
        String messaging = str(attrs, MESSAGING_SYSTEM);
        if (messaging != null) {
            if (messaging.contains("sns")) {
                return Optional.of(NodeKind.SNS);
            }
            if (messaging.contains("sqs")) {
                return Optional.of(NodeKind.SQS);
            }
        }
        if (attrs.containsKey(FAAS_NAME) || attrs.containsKey(FAAS_INVOCATION_ID)) {
            return Optional.of(NodeKind.LAMBDA);
        }
        if ("SERVER".equals(spanKind)) {
            return Optional.of(NodeKind.HTTP_SERVER);
        }
        if ("CLIENT".equals(spanKind)) {
            return Optional.of(NodeKind.HTTP_CLIENT);
        }
        return Optional.empty();
    }

    private NodeLabel labelFor(NodeKind kind, Map<String, Object> attrs, String name) {
        return switch (kind) {
            case DYNAMODB -> NodeLabel.of("DynamoDB: " + firstTable(attrs));
            case SNS -> NodeLabel.of("SNS: " + topicName(attrs));
            case SQS -> NodeLabel.of("SQS: " + queueName(attrs));
            case SQL -> NodeLabel.of("SQL: " + sqlTarget(attrs));
            case HTTP_SERVER, HTTP_CLIENT -> {
                String method = method(attrs);
                String route = str(attrs, HTTP_ROUTE);
                String base = route != null && !route.isBlank() ? route : name;
                yield NodeLabel.of(method == null || method.isBlank() ? base : method + " " + base);
            }
            default -> NodeLabel.of(name);
        };
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> attributeMap(SpanData span) {
        Map<String, Object> map = new LinkedHashMap<>();
        span.getAttributes().forEach((k, v) -> map.put(k.getKey(), v));
        return map;
    }

    private static String str(Map<String, Object> attrs, String key) {
        Object v = attrs.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                items.add(String.valueOf(item));
            }
            return String.join(",", items);
        }
        return String.valueOf(v);
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String firstTable(Map<String, Object> attrs) {
        String tables = str(attrs, AWS_DYNAMO_TABLES);
        if (tables == null || tables.isBlank()) {
            return "?";
        }
        return tables.split(",", 2)[0].trim();
    }

    private static String topicName(Map<String, Object> attrs) {
        String arn = str(attrs, AWS_SNS_TOPIC);
        if (arn == null) {
            return str(attrs, MESSAGING_DESTINATION);
        }
        String[] parts = arn.split(":");
        return parts.length > 0 ? parts[parts.length - 1] : arn;
    }

    private static String queueName(Map<String, Object> attrs) {
        String url = str(attrs, AWS_SQS_QUEUE);
        if (url == null) {
            return str(attrs, MESSAGING_DESTINATION);
        }
        String[] parts = url.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : url;
    }

    private static String sqlTarget(Map<String, Object> attrs) {
        String collection = str(attrs, DB_COLLECTION);
        if (collection != null && !collection.isBlank()) {
            return collection;
        }
        String namespace = str(attrs, DB_NAMESPACE);
        if (namespace != null && !namespace.isBlank()) {
            return namespace;
        }
        return str(attrs, DB_SYSTEM);
    }

    private static String method(Map<String, Object> attrs) {
        String m = str(attrs, HTTP_METHOD);
        return m != null ? m : "";
    }

    private static String rpcOrDbOperation(Map<String, Object> attrs) {
        String rpc = str(attrs, RPC_METHOD);
        if (rpc != null) {
            return rpc;
        }
        return str(attrs, DB_OPERATION);
    }
}
