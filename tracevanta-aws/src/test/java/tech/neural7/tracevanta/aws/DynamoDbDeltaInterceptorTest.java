package tech.neural7.tracevanta.aws;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.model.MutationFidelity;
import tech.neural7.tracevanta.model.MutationKind;
import tech.neural7.tracevanta.spi.DataMutationChannel;
import tech.neural7.tracevanta.spi.MutationEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contrato do ADR-003/R-01 exercitado pelo caminho REAL do pipeline do SDK v2:
 * a captura acontece no {@code modifyResponse} (o {@code afterExecution} recebe
 * a resposta já restaurada) e a restauração devolve a semântica original.
 */
class DynamoDbDeltaInterceptorTest {

    private SdkTracerProvider provider;

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
        DataMutationChannel.setSink(null);
    }

    private DynamoDbDeltaInterceptor interceptor() {
        return new DynamoDbDeltaInterceptor(TraceVantaConfig.defaults());
    }

    private DynamoDbDeltaInterceptor interceptorNoCapture() {
        return new DynamoDbDeltaInterceptor(TraceVantaConfig.builder().dynamoDbCaptureBefore(false).build());
    }

    // ---------------------------------------------------------------- elevação

    @Test
    void elevatesReturnValuesFromNoneToAllOld() {
        PutItemRequest original = PutItemRequest.builder()
                .tableName("orders")
                .item(Map.of("pk", AttributeValue.fromS("ORDER#1")))
                .returnValues(ReturnValue.NONE)
                .build();
        Context.ModifyRequest ctx = mock(Context.ModifyRequest.class);
        when(ctx.request()).thenReturn(original);
        ExecutionAttributes attrs = ExecutionAttributes.builder().build();

        var modified = interceptor().modifyRequest(ctx, attrs);

        assertThat(modified).isInstanceOf(PutItemRequest.class);
        assertThat(((PutItemRequest) modified).returnValues()).isEqualTo(ReturnValue.ALL_OLD);
        assertThat(attrs.getAttribute(DynamoDbDeltaInterceptor.TV_ELEVATED)).isTrue();
    }

    @Test
    void elevatesUpdateItemToAllNewForExactAfter() {
        UpdateItemRequest original = UpdateItemRequest.builder()
                .tableName("orders")
                .key(Map.of("pk", AttributeValue.fromS("ORDER#1")))
                .returnValues(ReturnValue.NONE)
                .build();
        Context.ModifyRequest ctx = mock(Context.ModifyRequest.class);
        when(ctx.request()).thenReturn(original);

        var modified = interceptor().modifyRequest(ctx, ExecutionAttributes.builder().build());

        assertThat(((UpdateItemRequest) modified).returnValues()).isEqualTo(ReturnValue.ALL_NEW);
    }

    @Test
    void doesNotElevateWhenCaptureDisabled() {
        PutItemRequest original = PutItemRequest.builder()
                .tableName("orders")
                .returnValues(ReturnValue.NONE)
                .build();
        Context.ModifyRequest ctx = mock(Context.ModifyRequest.class);
        when(ctx.request()).thenReturn(original);

        var modified = interceptorNoCapture().modifyRequest(ctx, ExecutionAttributes.builder().build());

        assertThat(modified).isSameAs(original);
    }

    @Test
    void doesNotElevateWhenDevAlreadyAskedForValues() {
        PutItemRequest original = PutItemRequest.builder()
                .tableName("orders")
                .returnValues(ReturnValue.ALL_NEW)
                .build();
        Context.ModifyRequest ctx = mock(Context.ModifyRequest.class);
        when(ctx.request()).thenReturn(original);

        var modified = interceptor().modifyRequest(ctx, ExecutionAttributes.builder().build());

        assertThat(modified).isSameAs(original);
    }

    // ---------------------------------------------------------------- captura + restauração (R-01)

    @Test
    void capturesPutItemDeltaAndRestoresResponseInTheSamePhase() {
        List<MutationEvent> published = new ArrayList<>();
        DataMutationChannel.setSink(published::add);
        Tracer tracer = tracer();

        // o request que chega ao modifyResponse já foi ELEVADO (ALL_OLD) pelo próprio interceptor
        PutItemRequest request = PutItemRequest.builder()
                .tableName("orders")
                .item(Map.of("pk", AttributeValue.fromS("ORDER#88291"),
                        "total", AttributeValue.fromN("250"),
                        "status", AttributeValue.fromS("PENDING")))
                .returnValues(ReturnValue.ALL_OLD)
                .build();
        PutItemResponse response = PutItemResponse.builder()
                .attributes(Map.of("pk", AttributeValue.fromS("ORDER#88291"),
                        "total", AttributeValue.fromN("200"),
                        "status", AttributeValue.fromS("DRAFT")))
                .build();
        Context.ModifyResponse ctx = mock(Context.ModifyResponse.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.response()).thenReturn(response);
        ExecutionAttributes attrs = ExecutionAttributes.builder()
                .put(DynamoDbDeltaInterceptor.TV_ELEVATED, true)
                .build();

        Span span = tracer.spanBuilder("DynamoDb.PutItem").startSpan();
        software.amazon.awssdk.core.SdkResponse restored;
        try (Scope ignored = span.makeCurrent()) {
            restored = interceptor().modifyResponse(ctx, attrs);
        } finally {
            span.end();
        }

        // 1) o delta foi capturado com os valores ANTES da restauração
        assertThat(published).hasSize(1);
        var mutation = published.get(0).mutation();
        assertThat(mutation.kind()).isEqualTo(MutationKind.CREATE);
        assertThat(mutation.target()).isEqualTo("orders");
        assertThat(mutation.key()).isEqualTo("ORDER#88291");
        assertThat(mutation.fidelity()).isEqualTo(MutationFidelity.EXACT);
        assertThat(mutation.before().get("total").asText()).isEqualTo("200");
        assertThat(mutation.after().get("total").asText()).isEqualTo("250");
        assertThat(mutation.deltas()).extracting(tech.neural7.tracevanta.model.FieldDelta::path)
                .contains("total", "status");
        assertThat(published.get(0).spanId()).isEqualTo(span.getSpanContext().getSpanId());
        // 2) a resposta devolvida ao dev foi restaurada (sem os atributos que ele não pediu)
        assertThat(((PutItemResponse) restored).attributes()).isNullOrEmpty();
    }

    @Test
    void capturesUpdateItemAfterExactWithDeclaredAbsentBefore() {
        List<MutationEvent> published = new ArrayList<>();
        DataMutationChannel.setSink(published::add);
        Tracer tracer = tracer();

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName("orders")
                .key(Map.of("pk", AttributeValue.fromS("ORDER#9")))
                .returnValues(ReturnValue.ALL_NEW) // como elevado pelo interceptor
                .build();
        UpdateItemResponse response = UpdateItemResponse.builder()
                .attributes(Map.of("pk", AttributeValue.fromS("ORDER#9"),
                        "status", AttributeValue.fromS("CONFIRMED")))
                .build();
        Context.ModifyResponse ctx = mock(Context.ModifyResponse.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.response()).thenReturn(response);

        Span span = tracer.spanBuilder("DynamoDb.UpdateItem").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            interceptor().modifyResponse(ctx, ExecutionAttributes.builder()
                    .put(DynamoDbDeltaInterceptor.TV_ELEVATED, true).build());
        } finally {
            span.end();
        }

        assertThat(published).hasSize(1);
        assertThat(published.get(0).mutation().kind()).isEqualTo(MutationKind.UPDATE);
        assertThat(published.get(0).mutation().after().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(published.get(0).mutation().before()).isNull(); // ausência declarada (I3)
        assertThat(published.get(0).mutation().fidelity()).isEqualTo(MutationFidelity.EXACT);
    }

    @Test
    void keepsResponseWhenDevAskedForAttributesHimself() {
        PutItemResponse withAttributes = PutItemResponse.builder()
                .attributes(Map.of("pk", AttributeValue.fromS("ORDER#1")))
                .build();
        Context.ModifyResponse ctx = mock(Context.ModifyResponse.class);
        when(ctx.request()).thenReturn(PutItemRequest.builder().returnValues(ReturnValue.ALL_OLD).build());
        when(ctx.response()).thenReturn(withAttributes);

        var restored = interceptor().modifyResponse(ctx, ExecutionAttributes.builder().build());

        assertThat(restored).isSameAs(withAttributes);
    }

    @Test
    void skipsCaptureWithoutActiveSpan() {
        List<MutationEvent> published = new ArrayList<>();
        DataMutationChannel.setSink(published::add);

        PutItemRequest request = PutItemRequest.builder()
                .tableName("orders")
                .item(Map.of("pk", AttributeValue.fromS("ORDER#1")))
                .build();
        Context.ModifyResponse ctx = mock(Context.ModifyResponse.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.response()).thenReturn(PutItemResponse.builder().build());

        interceptor().modifyResponse(ctx, ExecutionAttributes.builder().build());

        assertThat(published).isEmpty();
    }

    @Test
    void declaresUnavailableForTransactWriteItems() {
        List<MutationEvent> published = new ArrayList<>();
        DataMutationChannel.setSink(published::add);
        Tracer tracer = tracer();

        Context.ModifyResponse ctx = mock(Context.ModifyResponse.class);
        when(ctx.request()).thenReturn(TransactWriteItemsRequest.builder().build());
        when(ctx.response()).thenReturn(mock(software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse.class));

        Span span = tracer.spanBuilder("DynamoDb.TransactWriteItems").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            interceptor().modifyResponse(ctx, ExecutionAttributes.builder().build());
        } finally {
            span.end();
        }

        assertThat(published).hasSize(1);
        assertThat(published.get(0).mutation().fidelity()).isEqualTo(MutationFidelity.UNAVAILABLE);
    }

    @Test
    void capturesDeleteWithNullAfter() {
        List<MutationEvent> published = new ArrayList<>();
        DataMutationChannel.setSink(published::add);
        Tracer tracer = tracer();

        DeleteItemRequest request = DeleteItemRequest.builder()
                .tableName("orders")
                .key(Map.of("pk", AttributeValue.fromS("ORDER#9")))
                .build();
        var response = DeleteItemResponse.builder()
                .attributes(Map.of("pk", AttributeValue.fromS("ORDER#9")))
                .build();
        Context.ModifyResponse ctx = mock(Context.ModifyResponse.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.response()).thenReturn(response);

        Span span = tracer.spanBuilder("DynamoDb.DeleteItem").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            interceptor().modifyResponse(ctx, ExecutionAttributes.builder().build());
        } finally {
            span.end();
        }

        assertThat(published).hasSize(1);
        assertThat(published.get(0).mutation().kind()).isEqualTo(MutationKind.DELETE);
        assertThat(published.get(0).mutation().after()).isNull();
        assertThat(published.get(0).mutation().before().get("pk").asText()).isEqualTo("ORDER#9");
    }

    // ---------------------------------------------------------------- helpers

    private Tracer tracer() {
        provider = SdkTracerProvider.builder().build();
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("test");
    }
}
