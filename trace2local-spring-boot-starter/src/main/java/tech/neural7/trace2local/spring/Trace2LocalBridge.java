package tech.neural7.trace2local.spring;

import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Trace2LocalEvent;

import java.util.function.Consumer;

/**
 * Ponte estática entre a autoconfiguração Spring e o mecanismo SPI do SDK OTel.
 * O {@link Trace2LocalOtelConfigurer} é carregado por {@code ServiceLoader} quando
 * o dev usa o autoconfigure do OpenTelemetry; o sink aqui é populado quando o
 * pipeline do Trace2Local sobe. Como a leitura é feita no MOMENTO DO EVENTO, a
 * ordem de inicialização dos beans não importa.
 */
public final class Trace2LocalBridge {

    private static volatile Consumer<Trace2LocalEvent> sink;
    private static volatile Trace2LocalConfig config;

    private Trace2LocalBridge() {}

    public static void set(Consumer<Trace2LocalEvent> eventSink, Trace2LocalConfig cfg) {
        sink = eventSink;
        config = cfg;
    }

    public static void clear() {
        sink = null;
        config = null;
    }

    static Consumer<Trace2LocalEvent> sink() {
        return sink;
    }

    static Trace2LocalConfig config() {
        return config != null ? config : Trace2LocalConfig.defaults();
    }
}
