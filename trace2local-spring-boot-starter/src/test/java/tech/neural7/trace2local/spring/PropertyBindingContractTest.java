package tech.neural7.trace2local.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tech.neural7.trace2local.config.JdbcMutationCapture;
import tech.neural7.trace2local.config.RedactionMode;
import tech.neural7.trace2local.config.Trace2LocalConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de nomes de propriedade da SPEC §5.4: TODAS as propriedades dotted
 * documentadas DEVEM ligar no Trace2LocalConfig (um nome que não liga é config
 * silenciosamente ignorada — bug de segurança potencial, ex.: redaction.mode).
 */
class PropertyBindingContractTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Trace2LocalAutoConfiguration.class))
            .withPropertyValues(
                    "spring.profiles.active=dev",
                    "trace2local.port=0",
                    "trace2local.redaction.mode=keys",
                    "trace2local.buffer.capacity=1234",
                    "trace2local.retention.max-executions=42",
                    "trace2local.payload.max-bytes=9999",
                    "trace2local.aws.dynamodb.capture-before=false",
                    "trace2local.jdbc.mutation-capture=inferred",
                    "trace2local.station.endpoint=http://127.0.0.1:19876",
                    "trace2local.station.token=sekrit-token-123",
                    "trace2local.flush-timeout-ms=333",
                    "trace2local.allow-non-loopback=false");

    @Test
    void everySpecPropertyNameBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Trace2LocalConfig cfg = context.getBean(Trace2LocalConfig.class);
            assertThat(cfg.redactionMode()).isEqualTo(RedactionMode.KEYS);
            assertThat(cfg.bufferCapacity()).isEqualTo(1234);
            assertThat(cfg.retentionMaxExecutions()).isEqualTo(42);
            assertThat(cfg.payloadMaxBytes()).isEqualTo(9999);
            assertThat(cfg.dynamoDbCaptureBefore()).isFalse();
            assertThat(cfg.jdbcMutationCapture()).isEqualTo(JdbcMutationCapture.INFERRED);
            assertThat(cfg.stationEndpoint()).isEqualTo("http://127.0.0.1:19876");
            assertThat(cfg.stationToken()).isEqualTo("sekrit-token-123");
            assertThat(cfg.flushTimeoutMs()).isEqualTo(333);
            assertThat(cfg.allowNonLoopback()).isFalse();
        });
    }
}
