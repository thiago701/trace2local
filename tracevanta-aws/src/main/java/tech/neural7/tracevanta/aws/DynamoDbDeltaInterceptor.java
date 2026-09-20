package tech.neural7.tracevanta.aws;

import io.opentelemetry.api.trace.Span;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import tech.neural7.tracevanta.config.RedactionMode;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.DeltaCalculator;
import tech.neural7.tracevanta.internal.Redactor;
import tech.neural7.tracevanta.model.DataMutation;
import tech.neural7.tracevanta.model.FieldDelta;
import tech.neural7.tracevanta.model.MutationFidelity;
import tech.neural7.tracevanta.model.MutationKind;
import tech.neural7.tracevanta.spi.DataMutationChannel;
import tech.neural7.tracevanta.spi.MutationEvent;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;


/**
 * Captura do delta de dados do DynamoDB com fidelidade EXACT (ADR-003 / SPEC §4.10).
 *
 * <p>⚠️ EFEITO COLATERAL DECLARADO: para obter o {@code before}, este interceptor
 * ELEVA {@code ReturnValues} de {@code NONE} para {@code ALL_OLD} (e {@code ALL_NEW}
 * em {@code UpdateItem}) na requisição do desenvolvedor — e RESTAURA a resposta
 * original em {@code modifyResponse}, para o código da aplicação não ver os
 * atributos que não pediu (Risco R-01). A elevação é desligável via
 * {@code tracevanta.aws.dynamodb.capture-before=false} e ativa apenas em perfil
 * de desenvolvimento (decisão D-3 do GATE 1).
 *
 * <p><b>Ordem crítica do pipeline do AWS SDK v2:</b> o {@code afterExecution}
 * recebe a resposta JÁ RESTAURADA por este próprio interceptor (o
 * {@code BaseClientHandler} propaga o context modificado). Por isso a captura
 * acontece DENTRO do {@code modifyResponse}, antes da restauração — capturar em
 * {@code afterExecution} perderia o {@code before}/{@code after} no fluxo real.
 */
public final class DynamoDbDeltaInterceptor implements ExecutionInterceptor {

    static final software.amazon.awssdk.core.interceptor.ExecutionAttribute<Boolean> TV_ELEVATED =
            new software.amazon.awssdk.core.interceptor.ExecutionAttribute<>("tracevanta.aws.dynamodb.elevated");

    private final boolean captureBefore;
    private final RedactionMode redactionMode;
    private final int payloadMaxBytes;

    public DynamoDbDeltaInterceptor(TraceVantaConfig cfg) {
        this.captureBefore = cfg.dynamoDbCaptureBefore();
        this.redactionMode = cfg.redactionMode();
        this.payloadMaxBytes = cfg.payloadMaxBytes();
    }

    @Override
    public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
        SdkRequest request = context.request();
        if (!captureBefore) {
            return request;
        }
        if (request instanceof PutItemRequest put
                && (put.returnValues() == null || put.returnValues() == ReturnValue.NONE)) {
            executionAttributes.putAttribute(TV_ELEVATED, true);
            return put.toBuilder().returnValues(ReturnValue.ALL_OLD).build();
        }
        if (request instanceof UpdateItemRequest update
                && (update.returnValues() == null || update.returnValues() == ReturnValue.NONE)) {
            executionAttributes.putAttribute(TV_ELEVATED, true);
            // a API do DynamoDB devolve UM conjunto de valores por chamada;
            // ALL_NEW dá o `after` exato — o `before` fica null, declarado na UI (I3)
            return update.toBuilder().returnValues(ReturnValue.ALL_NEW).build();
        }
        if (request instanceof DeleteItemRequest delete
                && (delete.returnValues() == null || delete.returnValues() == ReturnValue.NONE)) {
            executionAttributes.putAttribute(TV_ELEVATED, true);
            return delete.toBuilder().returnValues(ReturnValue.ALL_OLD).build();
        }
        return request;
    }

    @Override
    public SdkResponse modifyResponse(Context.ModifyResponse context, ExecutionAttributes executionAttributes) {
        SdkResponse response = context.response();
        // 1) captura ANTES de restaurar — o afterExecution veria a resposta já sem os atributos
        captureDelta(context.request(), response);
        // 2) R-01: devolve ao código da aplicação a resposta SEM os atributos que o
        //    TraceVanta pediu — restaurando a semântica original do ReturnValues=NONE.
        //    Só restaura quando FOI o TraceVanta quem elevou; se o dev pediu os
        //    atributos, eles são dele e ficam.
        if (!captureBefore || !Boolean.TRUE.equals(executionAttributes.getAttribute(TV_ELEVATED))) {
            return response;
        }
        try {
            if (response instanceof PutItemResponse put) {
                return put.toBuilder().attributes((Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>) null).build();
            }
            if (response instanceof UpdateItemResponse update) {
                return update.toBuilder()
                        .attributes((Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>) null)
                        .build();
            }
            if (response instanceof DeleteItemResponse delete) {
                return delete.toBuilder().attributes((Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>) null).build();
            }
        } catch (Throwable ignored) {
            // restauração best-effort: nunca quebra a chamada do dev
        }
        return response;
    }

    private void captureDelta(SdkRequest request, SdkResponse response) {
        try {
            Span span = Span.current();
            if (!span.getSpanContext().isValid()) {
                return; // sem span ativo, não há com o que correlacionar
            }
            DataMutation mutation = null;
            if (request instanceof PutItemRequest put && response instanceof PutItemResponse putResp) {
                var before = captureBefore ? putResp.attributes() : null; // ALL_OLD (elevado ou pedido pelo dev)
                var after = put.item();
                mutation = mutation(MutationKind.CREATE, put.tableName(), keyOrItem(null, after),
                        AttributeValues.toJson(before), AttributeValues.toJson(after), MutationFidelity.EXACT);
            } else if (request instanceof UpdateItemRequest update && response instanceof UpdateItemResponse updateResp) {
                ReturnValue requested = update.returnValues();
                if (requested == ReturnValue.ALL_OLD) {
                    // o dev pediu o before: é o que a resposta tem
                    mutation = mutation(MutationKind.UPDATE, update.tableName(), AttributeValues.keyOf(update.key()),
                            AttributeValues.toJson(updateResp.attributes()), null, MutationFidelity.EXACT);
                } else if (requested == ReturnValue.ALL_NEW) {
                    // after exato (elevado por nós ou pedido pelo dev); before declarado ausente (I3)
                    mutation = mutation(MutationKind.UPDATE, update.tableName(), AttributeValues.keyOf(update.key()),
                            null, AttributeValues.toJson(updateResp.attributes()), MutationFidelity.EXACT);
                } else if (captureBefore) {
                    // NONE sem elevação não deveria acontecer; honestidade: delta indisponível
                    mutation = new DataMutation(MutationKind.UPDATE, update.tableName(),
                            AttributeValues.keyOf(update.key()), null, null, List.of(), MutationFidelity.UNAVAILABLE);
                }
            } else if (request instanceof DeleteItemRequest delete && response instanceof DeleteItemResponse deleteResp) {
                var before = captureBefore ? deleteResp.attributes() : null; // ALL_OLD
                mutation = mutation(MutationKind.DELETE, delete.tableName(), AttributeValues.keyOf(delete.key()),
                        AttributeValues.toJson(before), null, MutationFidelity.EXACT);
            } else if (request instanceof GetItemRequest get && response instanceof GetItemResponse getResp) {
                mutation = mutation(MutationKind.READ_ONLY, get.tableName(), AttributeValues.keyOf(get.key()),
                        null, AttributeValues.toJson(getResp.item()), MutationFidelity.EXACT);
            } else if (request instanceof QueryRequest query && response instanceof QueryResponse queryResp) {
                mutation = mutation(MutationKind.READ_ONLY, query.tableName(), AttributeValues.keyOf(query.expressionAttributeValues()),
                        null, itemsToJson(queryResp.items()), MutationFidelity.EXACT);
            } else if (request instanceof ScanRequest scan && response instanceof ScanResponse scanResp) {
                mutation = mutation(MutationKind.READ_ONLY, scan.tableName(), "*",
                        null, itemsToJson(scanResp.items()), MutationFidelity.EXACT);
            } else if (request instanceof TransactWriteItemsRequest) {
                // declarado, não silenciado (SPEC §4.10)
                mutation = new DataMutation(MutationKind.UPDATE, "?", "?",
                        null, null, List.of(), MutationFidelity.UNAVAILABLE);
            }
            if (mutation == null) {
                return;
            }
            DataMutationChannel.publish(new MutationEvent(
                    span.getSpanContext().getSpanId(),
                    span.getSpanContext().getTraceId(),
                    mutation,
                    Instant.now()));
        } catch (Throwable ignored) {
            // a captura jamais pode afetar a operação do dev (SPEC §7.3)
        }
    }

    private DataMutation mutation(MutationKind kind, String target, String key,
                                  com.fasterxml.jackson.databind.JsonNode before,
                                  com.fasterxml.jackson.databind.JsonNode after,
                                  MutationFidelity fidelity) {
        var b = before != null ? Redactor.redactJson(before, redactionMode) : null;
        var a = after != null ? Redactor.redactJson(after, redactionMode) : null;
        if (b != null) {
            b = Redactor.capPayload(b, payloadMaxBytes);
        }
        if (a != null) {
            a = Redactor.capPayload(a, payloadMaxBytes);
        }
        List<FieldDelta> deltas = DeltaCalculator.compute(b, a);
        return new DataMutation(kind, target, key, b, a, deltas, fidelity);
    }

    private static com.fasterxml.jackson.databind.JsonNode itemsToJson(List<Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> items) {
        if (items == null) {
            return JsonSupportHolder.MAPPER.nullNode();
        }
        var array = JsonSupportHolder.MAPPER.createArrayNode();
        for (var item : items) {
            array.add(AttributeValues.toJson(item));
        }
        return array;
    }

    private static String keyOrItem(Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> key,
                                    Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item) {
        if (key != null && !key.isEmpty()) {
            return AttributeValues.keyOf(key);
        }
        if (item != null && !item.isEmpty()) {
            // preferência DETERMINÍSTICA: pk/id exatos > *Id/*Key no fim > primeiro atributo
            String best = null;
            int bestScore = -1;
            for (String candidate : item.keySet()) {
                String lower = candidate.toLowerCase(Locale.ROOT);
                int score = lower.equals("pk") ? 100
                        : lower.equals("id") ? 90
                        : lower.endsWith("id") ? 50
                        : lower.endsWith("key") ? 40
                        : 0;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best != null) {
                return AttributeValues.keyOf(Map.of(best, item.get(best)));
            }
            String first = item.keySet().iterator().next();
            return AttributeValues.keyOf(Map.of(first, item.get(first)));
        }
        return "?";
    }
}
