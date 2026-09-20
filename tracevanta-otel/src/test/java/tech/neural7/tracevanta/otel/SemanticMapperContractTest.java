package tech.neural7.tracevanta.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.model.NodeKind;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de contrato do SemanticMapper (ADR-008 / SPEC §10): fixture por tipo de
 * span, na versão de semconv suportada (OTel 1.66 / semconv 1.44). Bump do OTel
 * que quebre o mapeamento falha aqui — não na UI do dev.
 */
class SemanticMapperContractTest {

    private final SemanticMapper mapper = new DefaultSemanticMapper();

    @Test
    void mapsDynamoDbSpan() {
        SpanData span = span("DynamoDb.PutItem", SpanKind.CLIENT, Attributes.of(
                AttributeKey.stringKey("rpc.system"), "aws-api",
                AttributeKey.stringKey("rpc.service"), "DynamoDb",
                AttributeKey.stringKey("rpc.method"), "PutItem",
                AttributeKey.stringArrayKey("aws.dynamodb.table_names"), List.of("orders")));

        assertThat(mapper.kindOf(span)).contains(NodeKind.DYNAMODB);
        assertThat(mapper.labelOf(span).text()).isEqualTo("DynamoDB: orders");
        assertThat(mapper.inspectorFieldsOf(span)).containsEntry("operation", "PutItem");
    }

    @Test
    void mapsSnsPublishSpan() {
        SpanData span = span("Sns.Publish", SpanKind.PRODUCER, Attributes.of(
                AttributeKey.stringKey("rpc.system"), "aws-api",
                AttributeKey.stringKey("rpc.service"), "Sns",
                AttributeKey.stringKey("rpc.method"), "Publish",
                AttributeKey.stringKey("aws.sns.topic.arn"), "arn:aws:sns:us-east-1:000000000000:order-events"));

        assertThat(mapper.kindOf(span)).contains(NodeKind.SNS);
        assertThat(mapper.labelOf(span).text()).isEqualTo("SNS: order-events");
    }

    @Test
    void mapsSqsReceiveSpan() {
        SpanData span = span("Sqs.ReceiveMessage", SpanKind.CONSUMER, Attributes.of(
                AttributeKey.stringKey("rpc.system"), "aws-api",
                AttributeKey.stringKey("rpc.service"), "Sqs",
                AttributeKey.stringKey("rpc.method"), "ReceiveMessage",
                AttributeKey.stringKey("aws.sqs.queue.url"), "http://localhost:4566/000000000000/billing-queue"));

        assertThat(mapper.kindOf(span)).contains(NodeKind.SQS);
        assertThat(mapper.labelOf(span).text()).isEqualTo("SQS: billing-queue");
    }

    @Test
    void mapsJdbcSpanOnStableDbConventions() {
        SpanData span = span("UPDATE orders", SpanKind.CLIENT, Attributes.of(
                AttributeKey.stringKey("db.system.name"), "postgresql",
                AttributeKey.stringKey("db.operation.name"), "UPDATE",
                AttributeKey.stringKey("db.collection.name"), "orders",
                AttributeKey.stringKey("db.query.text"), "UPDATE orders SET total=? WHERE id=?"));

        assertThat(mapper.kindOf(span)).contains(NodeKind.SQL);
        assertThat(mapper.labelOf(span).text()).isEqualTo("SQL: orders");
        assertThat(mapper.inspectorFieldsOf(span)).containsEntry("db.operation", "UPDATE");
    }

    @Test
    void mapsHttpServerSpan() {
        SpanData span = span("POST /orders", SpanKind.SERVER, Attributes.of(
                AttributeKey.stringKey("http.request.method"), "POST",
                AttributeKey.stringKey("http.route"), "/orders"));

        assertThat(mapper.kindOf(span)).contains(NodeKind.HTTP_SERVER);
        assertThat(mapper.labelOf(span).text()).isEqualTo("POST /orders");
    }

    @Test
    void degradesGracefullyToUnknownNode() {
        SpanData span = span("mystery.span", SpanKind.INTERNAL, Attributes.empty());

        assertThat(mapper.kindOf(span)).isEmpty();
        assertThat(mapper.labelOf(span).text()).isEqualTo("mystery.span");
        assertThat(mapper.inspectorFieldsOf(span)).containsEntry("span.name", "mystery.span");
    }

    @Test
    void neverThrowsEvenOnNullOrBareSpans() {
        SpanData bare = span("bare", SpanKind.INTERNAL, Attributes.empty());
        assertThat(mapper.kindOf(null)).isEmpty();
        assertThat(mapper.labelOf(null).text()).isEqualTo("?");
        assertThat(mapper.kindOf(bare)).isEmpty();
        assertThat(mapper.labelOf(bare).text()).isEqualTo("bare");
        assertThat(mapper.inspectorFieldsOf(null)).isEmpty();
    }

    // ---------------------------------------------------------------- fixture

    private static SpanData span(String name, SpanKind kind, Attributes attrs) {
        return TestSpanData.builder()
                .setSpanContext(SpanContext.create("0123456789abcdef0123456789abcdef", "0123456789abcdef",
                        io.opentelemetry.api.trace.TraceFlags.getDefault(), io.opentelemetry.api.trace.TraceState.getDefault()))
                .setParentSpanContext(SpanContext.getInvalid())
                .setName(name)
                .setKind(kind)
                .setStartEpochNanos(1_000_000L)
                .setEndEpochNanos(2_000_000L)
                .setStatus(StatusData.unset())
                .setHasEnded(true)
                .setAttributes(attrs)
                .setTotalAttributeCount(attrs.size())
                .setTotalRecordedEvents(0)
                .setLinks(Collections.emptyList())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.empty())
                .build();
    }
}
