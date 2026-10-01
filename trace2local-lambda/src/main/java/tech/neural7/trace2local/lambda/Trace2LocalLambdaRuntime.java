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
    private final LogHttpPublisher logPublisher;
    /** Sem Station configurado (ex.: deploy real na AWS): passa direto, sem telemetria. */
    private final boolean disabled;
    /** Logs da última invocação (inspeção em teste e transporte). */
    private volatile List<tech.neural7.trace2local.model.LogEntry> lastInvocationLogs = List.of();

    private Trace2LocalLambdaRuntime(Trace2LocalConfig cfg) {
        this.cfg = cfg;
        this.disabled = true;
        this.sdk = null;
        this.tracer = null;
        this.mutationPublisher = null;
        this.logPublisher = null;
    }

    private Trace2LocalLambdaRuntime(Trace2LocalConfig cfg, SpanExporter exporter) {
        this.cfg = cfg;
        this.disabled = false;
        SdkTracerProvider provider = SdkTracerProvider.builder()
                // alwaysOn (e NÃO parentBased): o API Gateway/X-Ray do LocalStack injeta
                // X-Amzn-Trace-Id com Sampled=0 — com o sampler padrão a árvore INTEIRA seria
                // descartada em silêncio. Ferramenta local: toda execução é registrada.
                .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        this.sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                // trace context + baggage: o baggage de entrada (ex.: t2l.mock=<variação>) segue nas
                // chamadas de saída instrumentadas — é assim que a variação de mock chega ao parceiro
                .setPropagators(io.opentelemetry.context.propagation.ContextPropagators.create(
                        io.opentelemetry.context.propagation.TextMapPropagator.composite(
                                io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance(),
                                io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator.getInstance())))
                .build();
        this.tracer = sdk.getTracer("tech.neural7.trace2local:lambda");
        this.mutationPublisher = cfg.stationEndpoint() != null && !cfg.stationEndpoint().isBlank()
                ? new MutationHttpPublisher(cfg.stationEndpoint(), cfg.flushTimeoutMs(), cfg.stationToken())
                : null;
        this.logPublisher = cfg.stationEndpoint() != null && !cfg.stationEndpoint().isBlank()
                ? new LogHttpPublisher(cfg.stationEndpoint(), cfg.flushTimeoutMs(), cfg.stationToken())
                : null;
        // linha do tempo estilo CloudWatch (ADR-012): espelha stdout/stderr da função
        LambdaLogCapture.install();
        // os instrumentos (AWS SDK, JDBC, spans manuais) leem Trace2LocalOtel.get():
        // na Lambda não há Spring para registrar — o runtime se registra sozinho.
        tech.neural7.trace2local.otel.Trace2LocalOtel.register(sdk);
    }

    /** Cria com exportador OTLP/HTTP apontando para o Station. */
    public static Trace2LocalLambdaRuntime forStation(Trace2LocalConfig cfg) {
        if (cfg.stationEndpoint() == null || cfg.stationEndpoint().isBlank()) {
            // a MESMA função vai para a AWS real sem Station: o Trace2Local nunca derruba a
            // aplicação (regra 4 do AGENTS.md) — desliga-se com um aviso único
            java.util.logging.Logger.getLogger(Trace2LocalLambdaRuntime.class.getName()).warning(
                    "Trace2Local desligado nesta Lambda: TRACE2LOCAL_STATION_ENDPOINT não definido "
                    + "(esperado fora do ambiente local).");
            return new Trace2LocalLambdaRuntime(cfg);
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
        return around(lambdaContext, remoteParent, null, handler);
    }

    /**
     * Como acima, com {@code baggage} de entrada (W3C) — vira o baggage corrente durante
     * o handler e é propagado nas chamadas de saída instrumentadas.
     */
    public <T> T around(Context lambdaContext, io.opentelemetry.api.trace.SpanContext remoteParent,
                        io.opentelemetry.api.baggage.Baggage baggage, Callable<T> handler) throws Exception {
        return around(lambdaContext, remoteParent, baggage, null, handler);
    }

    /**
     * Forma completa: {@code trigger} (de {@link LambdaTriggerSemantics#of}) dá à raiz o
     * nome do gatilho ("pix-api · POST /pix/transfers") e o status HTTP da resposta.
     */
    public <T> T around(Context lambdaContext, io.opentelemetry.api.trace.SpanContext remoteParent,
                        io.opentelemetry.api.baggage.Baggage baggage, LambdaTriggerSemantics.Entry trigger,
                        Callable<T> handler) throws Exception {
        if (disabled) {
            return handler.call();
        }
        String functionName = lambdaContext != null && lambdaContext.getFunctionName() != null
                && !lambdaContext.getFunctionName().isBlank() ? lambdaContext.getFunctionName() : "lambda";
        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(
                        trigger != null && trigger.spanName() != null ? trigger.spanName() : functionName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(Trace2LocalAttributes.TRIGGER, Trace2LocalAttributes.TRIGGER_LAMBDA)
                .setAttribute(Trace2LocalAttributes.EXECUTION_ID, requestId(lambdaContext))
                .setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.FAAS_NAME, functionName)
                .setAttribute(tech.neural7.trace2local.otel.OtelAttributeNames.FAAS_INVOCATION_ID,
                        lambdaContext != null ? lambdaContext.getAwsRequestId() : "?");
        io.opentelemetry.context.Context parentContext = io.opentelemetry.context.Context.root();
        if (remoteParent != null && remoteParent.isValid()) {
            parentContext = parentContext.with(Span.wrap(remoteParent));
        }
        if (baggage != null && !baggage.isEmpty()) {
            parentContext = parentContext.with(baggage);
        }
        builder.setParent(parentContext);
        if (trigger != null) {
            trigger.attributes().forEach(builder::setAttribute);
        }
        Span root = builder.startSpan();
        boolean success = false;
        Throwable failure = null;
        LambdaLogCapture.Invocation invocation = LambdaLogCapture.begin(
                requestId(lambdaContext),
                root.getSpanContext().getTraceId(),
                lambdaContext != null ? lambdaContext.getFunctionName() : null,
                lambdaContext != null ? safeLogGroup(lambdaContext) : null,
                lambdaContext != null ? safeLogStream(lambdaContext) : null,
                lambdaContext != null ? lambdaContext.getMemoryLimitInMB() : 0);
        try (var scope = parentContext.with(root).makeCurrent()) {
            synchronized (pendingMutations) {
                pendingMutations.clear();
            }
            DataMutationChannel.setSink(this::collect);
            T result = handler.call();
            success = true;
            LambdaTriggerSemantics.onResult(trigger, root, result);
            return result;
        } catch (Throwable t) {
            failure = t;
            root.recordException(t);
            // a mensagem do status viaja no OTLP e vira o ErrorInfo do nó no Station
            // (o ingest lê status.message — sem descrição a árvore ficaria vermelha muda)
            root.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, String.valueOf(t));
            throw rethrow(t);
        } finally {
            DataMutationChannel.setSink(null);
            root.end();
            List<tech.neural7.trace2local.model.LogEntry> logs = LambdaLogCapture.end(invocation, failure);
            lastInvocationLogs = logs;
            // FLUSH SÍNCRONO — o ambiente congela (ADR-002 / SPEC §4.2)
            flushMutations();
            if (logPublisher != null) {
                logPublisher.publish(logs);
            }
            // cold start: a 1ª exportação paga a conexão e o carregamento de classes do cliente
            // HTTP — teto 5× maior só na primeira invocação do processo
            long budget = invocations.getAndIncrement() == 0 ? cfg.flushTimeoutMs() * 5L : cfg.flushTimeoutMs();
            try {
                io.opentelemetry.sdk.common.CompletableResultCode flushed = sdk.getSdkTracerProvider().forceFlush()
                        .join(budget, TimeUnit.MILLISECONDS);
                if (!flushed.isSuccess()) {
                    reportFlushMiss(budget);
                }
            } catch (Throwable ignored) {
                // estouro de teto: telemetria é best-effort — nunca derruba a função
                reportFlushMiss(budget);
            }
        }
    }

    private final java.util.concurrent.atomic.AtomicLong invocations = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong flushMisses = new java.util.concurrent.atomic.AtomicLong();

    /** Diagnóstico acionável (1ª vez e a cada 50): "por que meus traces não aparecem?". */
    private void reportFlushMiss(long budgetMs) {
        long n = flushMisses.incrementAndGet();
        if (n == 1 || n % 50 == 0) {
            System.err.println("WARN Trace2Local: o envio ao Station não confirmou em " + budgetMs + " ms (" + n
                    + "ª vez) — telemetria desta invocação pode ter sido descartada. Verifique TRACE2LOCAL_STATION_ENDPOINT ("
                    + cfg.stationEndpoint() + ") e, se a rede for lenta, aumente TRACE2LOCAL_FLUSH_TIMEOUT_MS.");
        }
    }

    /** {@code false} quando não há Station configurado (pass-through). */
    public boolean enabled() {
        return !disabled;
    }

    /** Logs (CloudWatch-like) da última invocação — útil em testes. */
    public List<tech.neural7.trace2local.model.LogEntry> lastInvocationLogs() {
        return lastInvocationLogs;
    }

    private static String safeLogGroup(Context ctx) {
        try {
            return ctx.getLogGroupName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeLogStream(Context ctx) {
        try {
            return ctx.getLogStreamName();
        } catch (Throwable t) {
            return null;
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
        if (disabled) {
            return;
        }
        sdk.getSdkTracerProvider().shutdown();
    }
}
