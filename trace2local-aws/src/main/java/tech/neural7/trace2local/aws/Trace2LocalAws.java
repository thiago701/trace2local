package tech.neural7.trace2local.aws;

import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import tech.neural7.trace2local.config.Trace2LocalConfig;

/**
 * Registro da instrumentação AWS do SDK v2 — sem agente (ADR-001 / PESQUISA):
 * o interceptor OTel cria os spans, o {@link DynamoDbDeltaInterceptor} captura o
 * delta. Uma linha por cliente:
 *
 * <pre>{@code
 * Trace2LocalAws.instrument(dynamoDbClientBuilder, traceVantaConfig);
 * }</pre>
 */
public final class Trace2LocalAws {

    private Trace2LocalAws() {}

    /** DynamoDB: spans OTel + delta before/after (ADR-003). */
    public static DynamoDbClientBuilder instrument(DynamoDbClientBuilder builder, Trace2LocalConfig cfg) {
        return builder.overrideConfiguration(o -> o
                .addExecutionInterceptor(otelAwsInterceptor())
                .addExecutionInterceptor(new DynamoDbDeltaInterceptor(cfg)));
    }

    /**
     * DynamoDB com READ-BACK pós-update (fecha o desvio "UpdateItem before+after"
     * da §4.10): a API do DynamoDB devolve UM conjunto por chamada — aqui o
     * {@code before} vem do {@code ALL_OLD} da resposta e o {@code after} EXATO
     * vem de uma releitura feita DENTRO do span do update (correlação correta,
     * sem span aninhado: o cliente de releitura é cru, sem interceptores).
     * Custo declarado: 1 GetItem extra por UpdateItem (capacidade de leitura).
     *
     * <p><b>Use POR ÚLTIMO na cadeia do builder</b> (o wrapper grava
     * {@code endpointOverride}/{@code region}/{@code credentialsProvider}
     * definidos DEPOIS dele); sem configuração explícita, a releitura usa a
     * mesma cadeia de resolução padrão do SDK (env vars/system properties).
     */
    public static DynamoDbClientBuilder instrumentWithReadBack(DynamoDbClientBuilder builder, Trace2LocalConfig cfg) {
        return (DynamoDbClientBuilder) java.lang.reflect.Proxy.newProxyInstance(
                DynamoDbClientBuilder.class.getClassLoader(),
                new Class<?>[] { DynamoDbClientBuilder.class },
                new ReadBackBuilder(builder, cfg));
    }

    /** SNS: spans OTel (a correlação SNS→SQS via AWSTraceHeader é do próprio OTel). */
    public static SnsClientBuilder instrument(SnsClientBuilder builder) {
        return builder.overrideConfiguration(o -> o.addExecutionInterceptor(otelAwsInterceptor()));
    }

    /** SQS: spans OTel com Link para o span produtor (SPEC §4.11). */
    public static SqsClientBuilder instrument(SqsClientBuilder builder) {
        return builder.overrideConfiguration(o -> o.addExecutionInterceptor(otelAwsInterceptor()));
    }

    /**
     * Interceptor de spans do OpenTelemetry para o AWS SDK v2 (library instrumentation).
     * RESOLUÇÃO LAZY: os beans do usuário são instanciados ANTES dos beans da
     * autoconfiguração — capturar o OpenTelemetry na construção do cliente pegaria
     * o global ainda travado em noop. O SDK do Trace2Local é resolvido na primeira
     * chamada real, quando o pipeline já está de pé.
     */
    public static ExecutionInterceptor otelAwsInterceptor() {
        return new LazyAwsSdkTelemetryInterceptor();
    }

    private static final class ReadBackBuilder implements java.lang.reflect.InvocationHandler {

        private final DynamoDbClientBuilder delegate;
        private final Trace2LocalConfig cfg;
        private java.net.URI endpoint;
        private software.amazon.awssdk.regions.Region region;
        private software.amazon.awssdk.identity.spi.IdentityProvider<software.amazon.awssdk.identity.spi.AwsCredentialsIdentity> credentials;

        ReadBackBuilder(DynamoDbClientBuilder delegate, Trace2LocalConfig cfg) {
            this.delegate = delegate;
            this.cfg = cfg;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "endpointOverride" -> endpoint = (java.net.URI) args[0];
                case "region" -> region = (software.amazon.awssdk.regions.Region) args[0];
                case "credentialsProvider" ->
                        credentials = (software.amazon.awssdk.identity.spi.IdentityProvider<software.amazon.awssdk.identity.spi.AwsCredentialsIdentity>) args[0];
                case "build" -> {
                    // cliente CRU de releitura: mesma config, SEM interceptores (sem span aninhado)
                    software.amazon.awssdk.services.dynamodb.DynamoDbClient raw = rawClient();
                    delegate.overrideConfiguration(o -> o
                            .addExecutionInterceptor(otelAwsInterceptor())
                            .addExecutionInterceptor(new DynamoDbDeltaInterceptor(cfg,
                                    (table, key) -> raw.getItem(r -> r.tableName(table).key(key)).item())));
                    return method.invoke(delegate, args);
                }
                default -> { /* apenas delega */ }
            }
            Object result = method.invoke(delegate, args);
            return result == delegate ? proxy : result;
        }

        private software.amazon.awssdk.services.dynamodb.DynamoDbClient rawClient() {
            DynamoDbClientBuilder builder = software.amazon.awssdk.services.dynamodb.DynamoDbClient.builder();
            if (endpoint != null) {
                builder.endpointOverride(endpoint);
            }
            if (region != null) {
                builder.region(region);
            }
            if (credentials != null) {
                builder.credentialsProvider(credentials);
            }
            return builder.build();
        }
    }

    private static final class LazyAwsSdkTelemetryInterceptor implements ExecutionInterceptor {

        private volatile ExecutionInterceptor delegate;

        private ExecutionInterceptor delegate() {
            ExecutionInterceptor current = delegate;
            if (current == null) {
                synchronized (this) {
                    if (delegate == null) {
                        delegate = io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry
                                .builder(tech.neural7.trace2local.otel.Trace2LocalOtel.get())
                                .build()
                                .createExecutionInterceptor();
                    }
                    current = delegate;
                }
            }
            return current;
        }

        @Override
        public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes executionAttributes) {
            delegate().beforeExecution(context, executionAttributes);
        }

        @Override
        public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
            return delegate().modifyRequest(context, executionAttributes);
        }

        @Override
        public void beforeMarshalling(Context.BeforeMarshalling context, ExecutionAttributes executionAttributes) {
            delegate().beforeMarshalling(context, executionAttributes);
        }

        @Override
        public void afterMarshalling(Context.AfterMarshalling context, ExecutionAttributes executionAttributes) {
            delegate().afterMarshalling(context, executionAttributes);
        }

        @Override
        public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
            return delegate().modifyHttpRequest(context, executionAttributes);
        }

        @Override
        public java.util.Optional<software.amazon.awssdk.core.sync.RequestBody> modifyHttpContent(
                Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
            return delegate().modifyHttpContent(context, executionAttributes);
        }

        @Override
        public java.util.Optional<software.amazon.awssdk.core.async.AsyncRequestBody> modifyAsyncHttpContent(
                Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
            return delegate().modifyAsyncHttpContent(context, executionAttributes);
        }

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
            delegate().beforeTransmission(context, executionAttributes);
        }

        @Override
        public void afterTransmission(Context.AfterTransmission context, ExecutionAttributes executionAttributes) {
            delegate().afterTransmission(context, executionAttributes);
        }

        @Override
        public software.amazon.awssdk.http.SdkHttpResponse modifyHttpResponse(
                Context.ModifyHttpResponse context, ExecutionAttributes executionAttributes) {
            return delegate().modifyHttpResponse(context, executionAttributes);
        }

        @Override
        public java.util.Optional<org.reactivestreams.Publisher<java.nio.ByteBuffer>> modifyAsyncHttpResponseContent(
                Context.ModifyHttpResponse context, ExecutionAttributes executionAttributes) {
            return delegate().modifyAsyncHttpResponseContent(context, executionAttributes);
        }

        @Override
        public java.util.Optional<java.io.InputStream> modifyHttpResponseContent(
                Context.ModifyHttpResponse context, ExecutionAttributes executionAttributes) {
            return delegate().modifyHttpResponseContent(context, executionAttributes);
        }

        @Override
        public void beforeUnmarshalling(Context.BeforeUnmarshalling context, ExecutionAttributes executionAttributes) {
            delegate().beforeUnmarshalling(context, executionAttributes);
        }

        @Override
        public void afterUnmarshalling(Context.AfterUnmarshalling context, ExecutionAttributes executionAttributes) {
            delegate().afterUnmarshalling(context, executionAttributes);
        }

        @Override
        public SdkResponse modifyResponse(Context.ModifyResponse context, ExecutionAttributes executionAttributes) {
            return delegate().modifyResponse(context, executionAttributes);
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes executionAttributes) {
            delegate().afterExecution(context, executionAttributes);
        }

        @Override
        public Throwable modifyException(Context.FailedExecution context, ExecutionAttributes executionAttributes) {
            return delegate().modifyException(context, executionAttributes);
        }

        @Override
        public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes executionAttributes) {
            delegate().onExecutionFailure(context, executionAttributes);
        }
    }
}
