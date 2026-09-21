package tech.neural7.trace2local.jdbc;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.config.JdbcMutationCapture;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Redactor;
import tech.neural7.trace2local.spi.DataMutationChannel;
import tech.neural7.trace2local.spi.MutationEvent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Trace2LocalJdbcTest {

    private final List<SpanData> spans = new ArrayList<>();
    private final List<MutationEvent> mutations = new ArrayList<>();
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new InMemoryExporter(spans)))
                .build();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        GlobalOpenTelemetry.set(sdk);
        DataMutationChannel.setSink(mutations::add);
    }

    @AfterEach
    void tearDown() {
        DataMutationChannel.setSink(null);
        sdk.getSdkTracerProvider().close();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void createsSpanWithStableDbAttributes() throws Exception {
        Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                .jdbcMutationCapture(JdbcMutationCapture.INFERRED)
                .build();
        DataSource wrapped = Trace2LocalJdbc.wrap(dataSource("UPDATE orders SET total = 251 WHERE pk = 'ORDER#1'", 1), cfg);

        int rows = wrapped.getConnection().prepareStatement("UPDATE orders SET total = 251 WHERE pk = 'ORDER#1'").executeUpdate();

        assertThat(rows).isEqualTo(1);
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(tech.neural7.trace2local.otel.OtelAttributeNames.DB_SYSTEM)))
                .isEqualTo("postgresql");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(tech.neural7.trace2local.otel.OtelAttributeNames.DB_OPERATION)))
                .isEqualTo("UPDATE");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(tech.neural7.trace2local.otel.OtelAttributeNames.DB_COLLECTION)))
                .isEqualTo("orders");
    }

    @Test
    void redactsSqlAtTheSource() throws Exception {
        Trace2LocalConfig cfg = Trace2LocalConfig.defaults();
        DataSource wrapped = Trace2LocalJdbc.wrap(dataSource("UPDATE users SET password = 'hunter2'", 1), cfg);

        wrapped.getConnection().prepareStatement("UPDATE users SET password = 'hunter2'").executeUpdate();

        SpanData span = spans.get(0);
        // db.query.text não pode sair com o literal sensível (redaction NA ORIGEM, ADR-007)
        String query = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(tech.neural7.trace2local.otel.OtelAttributeNames.DB_QUERY_TEXT));
        assertThat(query).contains(Redactor.REDACTED);
        assertThat(query).doesNotContain("hunter2");
    }

    @Test
    void publishesInferredMutationWhenConfigured() throws Exception {
        Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                .jdbcMutationCapture(JdbcMutationCapture.INFERRED)
                .build();
        DataSource wrapped = Trace2LocalJdbc.wrap(dataSource("DELETE FROM orders WHERE pk = 'ORDER#9'", 1), cfg);

        wrapped.getConnection().prepareStatement("DELETE FROM orders WHERE pk = 'ORDER#9'").executeUpdate();

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0).mutation().kind()).isEqualTo(tech.neural7.trace2local.model.MutationKind.DELETE);
        assertThat(mutations.get(0).mutation().fidelity())
                .isEqualTo(tech.neural7.trace2local.model.MutationFidelity.INFERRED);
        assertThat(mutations.get(0).spanId()).isEqualTo(spans.get(0).getSpanId());
    }

    @Test
    void publishesNothingWhenCaptureIsOff() throws Exception {
        Trace2LocalConfig cfg = Trace2LocalConfig.builder()
                .jdbcMutationCapture(JdbcMutationCapture.OFF)
                .build();
        DataSource wrapped = Trace2LocalJdbc.wrap(dataSource("DELETE FROM orders WHERE pk = 'x'", 1), cfg);

        wrapped.getConnection().prepareStatement("DELETE FROM orders WHERE pk = 'x'").executeUpdate();

        assertThat(mutations).isEmpty();
        assertThat(spans).hasSize(1); // o span continua existindo
    }

    @Test
    void errorsAreRecordedAndPropagated() throws Exception {
        Trace2LocalConfig cfg = Trace2LocalConfig.defaults();
        DataSource wrapped = Trace2LocalJdbc.wrap(dataSource("UPDATE orders SET x=1", null), cfg);

        try {
            wrapped.getConnection().prepareStatement("UPDATE orders SET x=1").executeUpdate();
            assertThat(true).as("deveria ter lançado").isFalse();
        } catch (Exception expected) {
            assertThat(spans.get(0).getStatus().getStatusCode().name()).isEqualTo("ERROR");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static DataSource dataSource(String sql, Integer affectedRows) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.executeUpdate()).thenAnswer(invocation -> {
            if (affectedRows == null) {
                throw new java.sql.SQLException("constraint violation");
            }
            return affectedRows;
        });
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getMetaData()).thenReturn(metaData);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    private static final class InMemoryExporter implements SpanExporter {
        private final List<SpanData> target;

        InMemoryExporter(List<SpanData> target) {
            this.target = target;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            target.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
