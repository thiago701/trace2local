package tech.neural7.tracevanta.jdbc;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import tech.neural7.tracevanta.config.JdbcMutationCapture;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.Redactor;
import tech.neural7.tracevanta.otel.OtelAttributeNames;
import tech.neural7.tracevanta.spi.DataMutationChannel;
import tech.neural7.tracevanta.spi.MutationEvent;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * Wrapper próprio de {@link DataSource} (plano B do Risco R-04 — sem depender do
 * {@code opentelemetry-jdbc} em alpha): cria spans CLIENT com os atributos
 * estáveis {@code db.*} e publica o delta {@code INFERRED} quando configurado.
 *
 * <pre>{@code
 * DataSource instrumented = TraceVantaJdbc.wrap(dataSource, traceVantaConfig);
 * }</pre>
 *
 * Níveis de captura (SPEC §4.10): {@code off} (padrão — só o span), {@code inferred}
 * (intenção por parse leve). {@code before-image} está reservado para a v0.2.
 */
public final class TraceVantaJdbc {

    private static final String TRACER_NAME = "tech.neural7.tracevanta:jdbc";

    private TraceVantaJdbc() {}

    public static DataSource wrap(DataSource delegate, TraceVantaConfig cfg) {
        if (cfg.jdbcMutationCapture() == JdbcMutationCapture.BEFORE_IMAGE) {
            throw new IllegalArgumentException(
                    "tracevanta.jdbc.mutation-capture=before-image não está implementado na v0.1 "
                    + "(exige SELECT prévio na mesma transação — SPEC §4.10). Use off ou inferred.");
        }
        Tracer tracer = tech.neural7.tracevanta.otel.TraceVantaOtel.get().getTracer(TRACER_NAME);
        boolean captureInferred = cfg.jdbcMutationCapture() == JdbcMutationCapture.INFERRED;
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName()) && method.getParameterCount() <= 2) {
                        Connection connection = invoke(method, delegate, args);
                        return wrapConnection(connection, tracer, cfg, captureInferred);
                    }
                    return invoke(method, delegate, args);
                });
    }

    private static Connection wrapConnection(Connection delegate, Tracer tracer,
                                            TraceVantaConfig cfg, boolean captureInferred) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "createStatement" -> {
                            Statement statement = invoke(method, delegate, args);
                            return wrapStatement(statement, tracer, cfg, captureInferred, dbSystem(delegate));
                        }
                        case "prepareStatement", "prepareCall" -> {
                            PreparedStatement statement = invoke(method, delegate, args);
                            String sql = args.length > 0 && args[0] instanceof String s ? s : null;
                            return wrapPrepared(statement, sql, tracer, cfg, captureInferred, dbSystem(delegate));
                        }
                        default -> {
                            return invoke(method, delegate, args);
                        }
                    }
                });
    }

    private static Statement wrapStatement(Statement delegate, Tracer tracer,
                                           TraceVantaConfig cfg, boolean captureInferred, String dbSystem) {
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute") && args != null && args.length > 0 && args[0] instanceof String sql) {
                        return execute(method, delegate, args, sql, tracer, cfg, captureInferred, dbSystem);
                    }
                    return invoke(method, delegate, args);
                });
    }

    private static PreparedStatement wrapPrepared(PreparedStatement delegate, String sql, Tracer tracer,
                                                  TraceVantaConfig cfg, boolean captureInferred, String dbSystem) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute") && (args == null || args.length == 0)) {
                        return execute(method, delegate, args, sql, tracer, cfg, captureInferred, dbSystem);
                    }
                    return invoke(method, delegate, args);
                });
    }

    private static Object execute(Method method, Object target, Object[] args, String sql,
                                  Tracer tracer, TraceVantaConfig cfg, boolean captureInferred,
                                  String dbSystem) throws Throwable {
        Span span = tracer.spanBuilder(spanName(sql))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(OtelAttributeNames.DB_SYSTEM, dbSystem)
                .setAttribute(OtelAttributeNames.DB_OPERATION, SqlMutationParser.operationOf(sql))
                .setAttribute(OtelAttributeNames.DB_QUERY_TEXT,
                        Redactor.redactString(scrubSqlLiterals(sql), cfg.redactionMode()))
                .startSpan();
        SqlMutationParser.tableOf(sql).ifPresent(table ->
                span.setAttribute(OtelAttributeNames.DB_COLLECTION, table));
        boolean success = false;
        try (var scope = span.makeCurrent()) {
            Object result = invoke(method, target, args);
            success = true;
            if (result instanceof Integer rows && rows > 0) {
                span.setAttribute(OtelAttributeNames.DB_ROWS_AFFECTED, rows);
            }
            publishInferred(captureInferred, sql, span);
            return result;
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR);
            throw t;
        } finally {
            if (success) {
                span.setStatus(StatusCode.OK);
            }
            span.end();
        }
    }

    /**
     * Redige os literais de string do SQL antes de ele virar atributo de span
     * (SPEC §4.10: {@code db.query.text} redigido). Nunca altera o comando
     * executado — só o que é observado.
     */
    static String scrubSqlLiterals(String sql) {
        if (sql == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (inString) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        // escape ''
                        i++;
                        continue;
                    }
                    inString = false;
                    sb.append('\'');
                } else {
                    inString = true;
                    sb.append("'").append(Redactor.REDACTED);
                }
            } else if (!inString) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void publishInferred(boolean enabled, String sql, Span span) {        if (!enabled || !span.getSpanContext().isValid()) {
            return;
        }
        SqlMutationParser.infer(sql).ifPresent(mutation -> DataMutationChannel.publish(
                new MutationEvent(span.getSpanContext().getSpanId(),
                        span.getSpanContext().getTraceId(), mutation, Instant.now())));
    }

    private static String spanName(String sql) {
        String op = SqlMutationParser.operationOf(sql);
        String table = SqlMutationParser.tableOf(sql).orElse("");
        return (op + " " + table).trim();
    }

    private static String dbSystem(Connection connection) {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product != null && !product.isBlank()) {
                return product.toLowerCase(java.util.Locale.ROOT);
            }
        } catch (SQLException ignored) {
            // metadados indisponíveis — degrada sem quebrar o dev
        }
        return "jdbc";
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return (T) method.invoke(target, args == null ? new Object[0] : args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
