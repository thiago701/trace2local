package tech.neural7.tracevanta.examples.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import tech.neural7.tracevanta.aws.TraceVantaAws;
import tech.neural7.tracevanta.config.TraceVantaConfig;

import java.net.URI;

/**
 * Clientes AWS instrumentados com UMA linha cada: spans OTel (library
 * instrumentation, sem agente) + delta do DynamoDB (ADR-003). O endpoint vem do
 * ambiente — LocalStack no docker-compose, AWS real se você definir as credenciais.
 */
@Configuration
public class AwsConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(TraceVantaConfig cfg,
                                         @Value("${localstack.endpoint}") String endpoint) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint));
        return TraceVantaAws.instrument(builder, cfg).build();
    }

    @Bean
    public SnsClient snsClient(@Value("${localstack.endpoint}") String endpoint) {
        SnsClientBuilder builder = SnsClient.builder()
                .endpointOverride(URI.create(endpoint));
        return TraceVantaAws.instrument(builder).build();
    }

    @Bean
    public software.amazon.awssdk.services.sqs.SqsClient sqsClient(@Value("${localstack.endpoint}") String endpoint) {
        // client CRU: o span do receive é criado MANUALMENTE pelo BillingConsumer
        // (o wrap do AwsSdkTelemetry para SQS exige resolução eager do OTel, que
        // acontece antes do SDK do TraceVanta subir — ver TraceVantaAws)
        return software.amazon.awssdk.services.sqs.SqsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .build();
    }
}
