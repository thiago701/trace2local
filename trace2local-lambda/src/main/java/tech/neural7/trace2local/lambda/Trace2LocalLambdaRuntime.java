package tech.neural7.trace2local.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.otel.Trace2LocalAttributes;
import tech.neural7.trace2local.spi.DataMutationChannel;
import tech.neural7.trace2local.spi.MutationEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Runtime do modo Companion em Lambda (ADR-002): abre o span raiz, executa o
 * handler e faz FLUSH SÍNCRONO no fim da invocação — obrigatório, porque o
 * ambiente congela entre invocações e um {@code BatchSpanProcessor} padrão
 * perde telemetria antes do worker acordar. Teto de tempo configurável
 * ({@code trace2local.flush-timeout-ms}, padrão 200 ms) com descarte silencioso
 * ao estourar (SPEC §4.2).
 */
public final class Trace2LocalLambdaRuntime {

    private final Trace2LocalConfig cfg;
    private final OpenTelemetrySdk sdk;
    private final Tracer tracer;
    private final List<MutationEvent> pendingMutations = new ArrayList<>();
    private final MutationHttpPublisher mutationPublisher;

    private Trace2LocalLambdaRuntime(Trace2LocalConfig cfg, SpanExporter exporter) {
        this.cfg = cfg;
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        this.sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(io.opentelemetry.context.propagation.ContextPropagators.create(
                        io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
                .build();
        this.tracer = sdk.getTracer("tech.neural7.trace2local:lambda");
        this.mutationPublisher = cfg.stationEndpoint() != null && !cfg.stationEndpoint().isBlank()
                ? new MutationHttpPublisher(cfg.stationEndpoint(), cfg.flushTimeoutMs(), cfg.stationToken())
                : null;
        // os instrumentos (AWS SDK, JDBC, spans manuais) leem Trace2LocalOtel.get():
        // na Lambda não há Spring para registrar — o runtime se registra sozinho.
        tech.neural7.trace2local.otel.Trace2LocalOtel.register(sdk);
    }

    /** Cria com exportador OTLP/HTTP apontando para o Station. */
    public static Trace2LocalLambdaRuntime forStation(Trace2LocalConfig cfg) {
        if (cfg.stationEndpoint() == null || cfg.stationEndpoint().isBlank()) {
            throw new IllegalStateException(
                    "trace2local.station.endpoint é obrigatório no modo Lambda (ADR-002) — "
                    + "defina a env TRACE2LOCAL_STATION_ENDPOINT ou use o construtor com config explícita.");
        }
        io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder builder =
                io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.builder()
                        .setEndpoint(stationTracesUrl(cfg));
        if (cfg.stationToken() != null && !cfg.stationToken().isBlank()) {
            builder.addHeader("Authorization", "Bearer " + cfg.stationToken());
        }
        return new Trace2LocalLambdaRuntime(cfg, builder.build());
    }

    /** Cria com exportador customizado (testes, outros backends). */
    public static Trace2LocalLambdaRuntime create(Trace2LocalConfig cfg, SpanExporter exporter) {
        return new Trace2LocalLambdaRuntime(cfg, exporter);
    }

    private static String stationTracesUrl(Trace2LocalConfig cfg) {
        String endpoint = cfg.stationEndpoint();
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint + "/v1/traces";
    }

    /** Executa o handler com span raiz, correlação de mutação e flush síncrono. */
    public <T> T around(Context lambdaContext, Callable<T> handler) throws Exception {
        return around(lambdaContext, null, handler);
    }

    /**
     * Como {@link #around(Context, Callable)}, mas com parent remoto: o span
     * raiz da invocação vira FILHO do span do produtor (SQS com
     * {@code AWSTraceHeader}) — a continuação aparece na MESMA árvore (SPEC §4.11).
     */
    public <T> T around(Context lambdaContext, io.opentelemetry.api.trace.SpanContext remoteParent,
                        Callable<T> handler) throws Exception {
        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(
                        lambdaContext != null ? lambdaContext.getFunctionName() : "lambda")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(Trace2LocalAttributes.TRIGGER, Trace2LocalAttributes.TRIGGER_LAMBDA)
                .setAttribute(Trace2LocalAttributes.EXECUTION_ID, requestId(lambdaContext))
                .setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.FAAS_NAME,
                        lambdaContext != null ? lambdaContext.getFunctionName() : "?")
                .setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.FAAS_INVOCATION_ID,
                        lambdaContext != null ? lambdaContext.getAwsRequestId() : "?");
        if (remoteParent != null && remoteParent.isValid()) {
            builder.setParent(io.opentelemetry.context.Context.root().with(Span.wrap(remoteParent)));
        }
        Span root = builder.startSpan();
        boolean success = false;
        try (var scope = root.makeCurrent()) {
            synchronized (pendingMutations) {
                pendingMutations.clear();
            }
            DataMutationChannel.setSink(this::collect);
            T result = handler.call();
            success = true;
            return result;
        } catch (Throwable t) {
            root.recordException(t);
            // a mensagem do status viaja no OTLP e vira o ErrorInfo do nó no Station
            // (o ingest lê status.message — sem descrição a árvore ficaria vermelha muda)
            root.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, String.valueOf(t));
            throw rethrow(t);
        } finally {
            DataMutationChannel.setSink(null);
            root.end();
            // FLUSH SÍNCRONO — o ambiente congela (ADR-002 / SPEC §4.2)
            flushMutations();
            try {
                sdk.getSdkTracerProvider().forceFlush()
                        .join(cfg.flushTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                // estouro de teto: descarte silencioso, telemetria é best-effort
            }
        }
    }

    private static String requestId(Context lambdaContext) {
        String id = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
        return id == null || id.isBlank() ? java.util.UUID.randomUUID().toString() : id;
    }

    private void collect(MutationEvent event) {
        synchronized (pendingMutations) {
            pendingMutations.add(event);
        }
    }

    private void flushMutations() {
        List<MutationEvent> batch;
        synchronized (pendingMutations) {
            if (pendingMutations.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingMutations);
            pendingMutations.clear();
        }
        if (mutationPublisher != null) {
            mutationPublisher.publish(batch);
        }
    }

    private static Exception rethrow(Throwable t) throws Exception {
        if (t instanceof Exception e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        throw new RuntimeException(t);
    }

    public void shutdown() {
        sdk.getSdkTracerProvider().shutdown();
    }
}
