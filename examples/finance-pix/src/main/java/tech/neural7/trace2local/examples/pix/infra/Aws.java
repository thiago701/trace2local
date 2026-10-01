package tech.neural7.trace2local.examples.pix.infra;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import tech.neural7.trace2local.aws.Trace2LocalAws;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.lambda.Trace2LocalLambda;

import java.net.URI;

/**
 * Clientes AWS instrumentados (uma linha por cliente — ADR-001): spans OTel do SDK,
 * delta EXACT do DynamoDB com releitura pós-update, e cliente HTTP leve
 * ({@code url-connection-client}) que funciona igual na JVM e no binário nativo.
 */
public final class Aws {

    public static final Trace2LocalConfig CFG = Trace2LocalLambda.configFromEnv();
    private static final String ENDPOINT = Env.get("AWS_ENDPOINT_URL", null);
    private static final Region REGION = Region.of(Env.get("AWS_REGION", "us-east-1"));

    private static volatile DynamoDbClient dynamo;
    private static volatile SqsClient sqs;
    private static volatile SnsClient sns;

    private Aws() {}

    public static DynamoDbClient dynamo() {
        if (dynamo == null) {
            synchronized (Aws.class) {
                if (dynamo == null) {
                    DynamoDbClientBuilder b = Trace2LocalAws.instrumentWithReadBack(DynamoDbClient.builder(), CFG)
                            .httpClient(UrlConnectionHttpClient.create()).region(REGION);
                    if (ENDPOINT != null) {
                        b.endpointOverride(URI.create(ENDPOINT));
                    }
                    dynamo = b.build();
                }
            }
        }
        return dynamo;
    }

    public static SqsClient sqs() {
        if (sqs == null) {
            synchronized (Aws.class) {
                if (sqs == null) {
                    SqsClientBuilder b = Trace2LocalAws.instrument(SqsClient.builder())
                            .httpClient(UrlConnectionHttpClient.create()).region(REGION);
                    if (ENDPOINT != null) {
                        b.endpointOverride(URI.create(ENDPOINT));
                    }
                    sqs = b.build();
                }
            }
        }
        return sqs;
    }

    public static SnsClient sns() {
        if (sns == null) {
            synchronized (Aws.class) {
                if (sns == null) {
                    SnsClientBuilder b = Trace2LocalAws.instrument(SnsClient.builder())
                            .httpClient(UrlConnectionHttpClient.create()).region(REGION);
                    if (ENDPOINT != null) {
                        b.endpointOverride(URI.create(ENDPOINT));
                    }
                    sns = b.build();
                }
            }
        }
        return sns;
    }
}
