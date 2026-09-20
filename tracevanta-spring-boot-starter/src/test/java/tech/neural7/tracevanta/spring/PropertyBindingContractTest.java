package tech.neural7.tracevanta.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tech.neural7.tracevanta.config.JdbcMutationCapture;
import tech.neural7.tracevanta.config.RedactionMode;
import tech.neural7.tracevanta.config.TraceVantaConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de nomes de propriedade da SPEC §5.4: TODAS as propriedades dotted
 * documentadas DEVEM ligar no TraceVantaConfig (um nome que não liga é config
 * silenciosamente ignorada — bug de segurança potencial, ex.: redaction.mode).
 */
class PropertyBindingContractTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TraceVantaAutoConfiguration.class))
            .withPropertyValues(
                    "spring.profiles.active=dev",
                    "tracevanta.port=0",
                    "tracevanta.redaction.mode=keys",
                    "tracevanta.buffer.capacity=1234",
                    "tracevanta.retention.max-executions=42",
                    "tracevanta.payload.max-bytes=9999",
                    "tracevanta.aws.dynamodb.capture-before=false",
                    "tracevanta.jdbc.mutation-capture=inferred",
                    "tracevanta.station.endpoint=http://127.0.0.1:19876",
                    "tracevanta.station.token=sekrit-token-123",
                    "tracevanta.flush-timeout-ms=333",
                    "tracevanta.allow-non-loopback=false");

    @Test
    void everySpecPropertyNameBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            TraceVantaConfig cfg = context.getBean(TraceVantaConfig.class);
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
