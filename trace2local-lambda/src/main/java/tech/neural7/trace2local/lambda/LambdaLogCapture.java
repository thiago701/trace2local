package tech.neural7.trace2local.lambda;

import io.opentelemetry.api.trace.Span;
import tech.neural7.trace2local.model.LogEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captura de logs da invocação Lambda no formato CloudWatch (ADR-012):
 * <ul>
 *   <li>{@code stdout}/{@code stderr} da função (SLF4J, JUL e {@code System.out}
 *       acabam ali no runtime Java da AWS) são espelhados — o original continua
 *       recebendo tudo, a captura só OBSERVA durante a invocação ativa;</li>
 *   <li>cada linha leva {@code traceId}/{@code RequestId} da invocação e o
 *       {@code spanId} ativo no momento da escrita (correlação linha → nó);</li>
 *   <li>o wrapper SINTETIZA {@code START}/{@code END}/{@code REPORT} com a
 *       mesma gramática da plataforma — marcadas {@code PLATFORM} para que o
 *       Station as suprima quando o CloudWatch real (LocalStack) as entregar.</li>
 * </ul>
 * Uma invocação por ambiente de execução (modelo da Lambda), então o estado
 * ativo é global ao processo.
 */
final class LambdaLogCapture {

    private static final Pattern LEVEL = Pattern.compile(
            "\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|SEVERE|FATAL)\\b");
    private static final DateTimeFormatter STREAM_DAY =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final int MAX_LINES_PER_INVOCATION = 2000;
    private static final int MAX_LINE_CHARS = 8192;

    private static final Object INSTALL_LOCK = new Object();
    private static volatile boolean installed;
    private static volatile Invocation active;
    /** Id do ambiente de execução (um por processo, como na AWS). */
    private static final String ENVIRONMENT_ID = java.util.UUID.randomUUID().toString().replace("-", "");
    private static final long PROCESS_START_NANOS = System.nanoTime();
    private static volatile boolean coldStartReported;

    private LambdaLogCapture() {}

    /** Invocação em curso: identidade CloudWatch + linhas coletadas. */
    static final class Invocation {
        final String requestId;
        final String traceId;
        final String functionName;
        final String logGroup;
        final String logStream;
        final int memoryLimitMb;
        final long startNanos = System.nanoTime();
        final Instant startedAt = Instant.now();
        final List<LogEntry> lines = new ArrayList<>();
        volatile int droppedLines;

        Invocation(String requestId, String traceId, String functionName,
                   String logGroup, String logStream, int memoryLimitMb) {
            this.requestId = requestId;
            this.traceId = traceId;
            this.functionName = functionName;
            this.logGroup = logGroup;
            this.logStream = logStream;
            this.memoryLimitMb = memoryLimitMb;
        }

        synchronized void add(LogEntry entry) {
            if (lines.size() >= MAX_LINES_PER_INVOCATION) {
                droppedLines++;
                return;
            }
            lines.add(entry);
        }

        synchronized List<LogEntry> drain() {
            List<LogEntry> out = new ArrayList<>(lines);
            lines.clear();
            return out;
        }
    }

    /** Instala os espelhos de stdout/stderr (idempotente). */
    static void install() {
        if (installed) {
            return;
        }
        synchronized (INSTALL_LOCK) {
            if (installed) {
                return;
            }
            System.setOut(new PrintStream(new LineTee(System.out, "stdout"), true, StandardCharsets.UTF_8));
            // stderr sem nível explícito NÃO vira ERROR: JUL escreve INFO ali (linha de cabeçalho sem nível)
            System.setErr(new PrintStream(new LineTee(System.err, "stderr"), true, StandardCharsets.UTF_8));
            installed = true;
        }
    }

    /** Abre a invocação e registra a linha START. */
    static Invocation begin(String requestId, String traceId, String functionName,
                            String logGroupName, String logStreamName, int memoryLimitMb) {
        String fn = functionName == null || functionName.isBlank() ? "lambda" : functionName;
        String group = notBlank(logGroupName) ? logGroupName : "/aws/lambda/" + fn;
        String stream = notBlank(logStreamName)
                ? logStreamName
                : STREAM_DAY.format(Instant.now()) + "/[$LATEST]" + ENVIRONMENT_ID;
        Invocation inv = new Invocation(requestId, traceId, fn, group, stream, memoryLimitMb);
        if (!coldStartReported) {
            inv.add(platform(inv, "INIT_START Runtime Version: java:21 (Trace2Local wrapper)"));
        }
        inv.add(platform(inv, "START RequestId: " + requestId + " Version: $LATEST"));
        active = inv;
        return inv;
    }

    /** Fecha a invocação: END + REPORT (com Init Duration no cold start). */
    static List<LogEntry> end(Invocation inv, Throwable failure) {
        if (active == inv) {
            active = null;
        }
        double durationMs = (System.nanoTime() - inv.startNanos) / 1_000_000.0;
        long billedMs = (long) Math.ceil(Math.max(1.0, durationMs));
        Runtime rt = Runtime.getRuntime();
        long usedMb = Math.max(1, (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        int memoryMb = inv.memoryLimitMb > 0 ? inv.memoryLimitMb : (int) Math.max(128, rt.maxMemory() / (1024 * 1024));
        if (failure != null) {
            inv.add(entry(inv, Instant.now(), "ERROR", "lambda",
                    "Invocation failed: " + failure.getClass().getName()
                            + (failure.getMessage() != null ? ": " + failure.getMessage() : ""),
                    currentSpanId(), LogEntry.LogSource.APP));
        }
        if (inv.droppedLines > 0) {
            inv.add(platform(inv, "[Trace2Local] " + inv.droppedLines
                    + " linha(s) descartada(s) por limite de " + MAX_LINES_PER_INVOCATION + " por invocação"));
        }
        inv.add(platform(inv, "END RequestId: " + inv.requestId));
        StringBuilder report = new StringBuilder()
                .append("REPORT RequestId: ").append(inv.requestId)
                .append("\tDuration: ").append(String.format(Locale.ROOT, "%.2f", durationMs)).append(" ms")
                .append("\tBilled Duration: ").append(billedMs).append(" ms")
                .append("\tMemory Size: ").append(memoryMb).append(" MB")
                .append("\tMax Memory Used: ").append(usedMb).append(" MB");
        if (!coldStartReported) {
            double initMs = (inv.startNanos - PROCESS_START_NANOS) / 1_000_000.0;
            report.append("\tInit Duration: ").append(String.format(Locale.ROOT, "%.2f", Math.max(0, initMs))).append(" ms");
            coldStartReported = true;
        }
        inv.add(platform(inv, report.toString()));
        return inv.drain();
    }

    private static LogEntry platform(Invocation inv, String message) {
        return entry(inv, Instant.now(), "PLATFORM", null, message, null, LogEntry.LogSource.PLATFORM);
    }

    private static LogEntry entry(Invocation inv, Instant at, String level, String logger, String message,
                                  String spanId, LogEntry.LogSource source) {
        // redação NA ORIGEM (ADR-007): o segredo não atravessa nem o fio até o Station
        String safe = source == LogEntry.LogSource.PLATFORM ? message
                : tech.neural7.trace2local.internal.TextRedactor.redact(message);
        return new LogEntry(at, level, logger, safe, inv.traceId, spanId, inv.requestId,
                inv.logGroup, inv.logStream, source);
    }

    static String detectLevel(String line, String fallback) {
        String head = line.length() > 120 ? line.substring(0, 120) : line;
        Matcher m = LEVEL.matcher(head);
        if (!m.find()) {
            return fallback;
        }
        return switch (m.group(1)) {
            case "WARNING" -> "WARN";
            case "SEVERE", "FATAL" -> "ERROR";
            default -> m.group(1);
        };
    }

    private static String currentSpanId() {
        try {
            var ctx = Span.current().getSpanContext();
            return ctx.isValid() ? ctx.getSpanId() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * OutputStream que repassa TUDO ao original e, durante uma invocação ativa,
     * acumula bytes por thread até o {@code \n} para registrar a linha.
     */
    static final class LineTee extends OutputStream {
        private final PrintStream original;
        private final String streamName;
        private final ThreadLocal<ByteArrayOutputStream> buffer =
                ThreadLocal.withInitial(() -> new ByteArrayOutputStream(256));

        LineTee(PrintStream original, String streamName) {
            this.original = original;
            this.streamName = streamName;
        }

        @Override
        public void write(int b) {
            original.write(b);
            if (active == null) {
                return;
            }
            capture(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            original.write(b, off, len);
            if (active == null) {
                return;
            }
            for (int i = off; i < off + len; i++) {
                capture(b[i]);
            }
        }

        private void capture(int b) {
            ByteArrayOutputStream buf = buffer.get();
            if (b == '\n') {
                emit(buf);
            } else if (buf.size() < MAX_LINE_CHARS) {
                buf.write(b);
            }
        }

        private void emit(ByteArrayOutputStream buf) {
            Invocation inv = active;
            String line = buf.toString(StandardCharsets.UTF_8).stripTrailing();
            buf.reset();
            if (inv == null || line.isEmpty()) {
                return;
            }
            inv.add(entry(inv, Instant.now(), detectLevel(line, "INFO"), streamName, line,
                    currentSpanId(), LogEntry.LogSource.APP));
        }

        @Override
        public void flush() {
            original.flush();
        }
    }
}
