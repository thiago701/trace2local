package tech.neural7.trace2local.model;

import java.time.Instant;

/**
 * Uma linha de log correlacionada à execução, no formato mental do CloudWatch
 * Logs ({@code logGroup → logStream → evento}). É o insumo da LINHA DO TEMPO da
 * UI: cada linha aponta para o trace/span que a produziu quando a correlação
 * existe — e declara a origem quando não existe (nunca presumida).
 *
 * @param timestamp  instante do evento
 * @param level      nível (INFO, WARN, ERROR, DEBUG…) ou {@code PLATFORM} para START/END/REPORT
 * @param logger     logger/categoria de origem (pode ser {@code null})
 * @param message    mensagem já REDIGIDA na origem (ADR-007)
 * @param traceId    trace OTel (hex 32) — {@code null} quando a correlação é só por RequestId
 * @param spanId     span ativo quando a linha foi emitida (hex 16) — pode ser {@code null}
 * @param requestId  RequestId da invocação Lambda (CloudWatch) — pode ser {@code null}
 * @param logGroup   grupo no estilo CloudWatch ({@code /aws/lambda/order-processor})
 * @param logStream  stream no estilo CloudWatch ({@code 2026/09/30/[$LATEST]…})
 * @param source     de onde a linha veio (declarado — invariante de honestidade)
 */
public record LogEntry(
        Instant timestamp,
        String level,
        String logger,
        String message,
        String traceId,
        String spanId,
        String requestId,
        String logGroup,
        String logStream,
        LogSource source) {

    /** Origem da linha — a UI mostra a procedência de cada uma. */
    public enum LogSource {
        /** Log da aplicação capturado em processo (Logback/JUL/stdout), correlacionado por span. */
        APP,
        /** Linha de plataforma (START/END/REPORT) SINTETIZADA pelo Trace2Local no wrapper Lambda. */
        PLATFORM,
        /** Linha lida do CloudWatch Logs (LocalStack ou AWS) — a fonte de verdade da plataforma. */
        CLOUDWATCH
    }

    /** Linha de plataforma Lambda ({@code START}/{@code END}/{@code REPORT})? */
    public boolean isPlatformLine() {
        if (message == null) {
            return false;
        }
        return message.startsWith("START RequestId:")
                || message.startsWith("END RequestId:")
                || message.startsWith("REPORT RequestId:")
                || message.startsWith("INIT_START");
    }

    public LogEntry withTraceId(String newTraceId) {
        return new LogEntry(timestamp, level, logger, message, newTraceId, spanId, requestId, logGroup, logStream, source);
    }

    public LogEntry withRequestId(String newRequestId) {
        return new LogEntry(timestamp, level, logger, message, traceId, spanId, newRequestId, logGroup, logStream, source);
    }

    public LogEntry withMessage(String newMessage) {
        return new LogEntry(timestamp, level, logger, newMessage, traceId, spanId, requestId, logGroup, logStream, source);
    }
}
