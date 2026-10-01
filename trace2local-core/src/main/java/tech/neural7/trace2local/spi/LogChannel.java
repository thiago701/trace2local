package tech.neural7.trace2local.spi;

import tech.neural7.trace2local.model.LogEntry;

import java.util.function.Consumer;

/**
 * Canal lateral de LOGS (espelho do {@link DataMutationChannel}): appenders de
 * log (Logback/JUL), o wrapper Lambda e o leitor de CloudWatch publicam aqui; o
 * pipeline em memória (Embedded/Station) consome e indexa por trace/RequestId
 * para a linha do tempo da UI. Publicar sem sink é um no-op seguro.
 */
public final class LogChannel {

    private static volatile Consumer<LogEntry> sink;

    private LogChannel() {}

    /** Configurado internamente pelo pipeline (Embedded/Station). */
    public static void setSink(Consumer<LogEntry> newSink) {
        sink = newSink;
    }

    /** Há alguém ouvindo? Evita montar a linha quando o canal está desligado. */
    public static boolean active() {
        return sink != null;
    }

    /** Nunca lança; o log do dev jamais pode quebrar por causa do Trace2Local. */
    public static void publish(LogEntry entry) {
        Consumer<LogEntry> current = sink;
        if (current != null && entry != null) {
            try {
                current.accept(entry);
            } catch (Throwable ignored) {
                // degradação preferida à falha (SPEC §7.3)
            }
        }
    }
}
