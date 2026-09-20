package tech.neural7.tracevanta.spring;

import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.TraceVantaEvent;

import java.util.function.Consumer;

/**
 * Ponte estática entre a autoconfiguração Spring e o mecanismo SPI do SDK OTel.
 * O {@link TraceVantaOtelConfigurer} é carregado por {@code ServiceLoader} quando
 * o dev usa o autoconfigure do OpenTelemetry; o sink aqui é populado quando o
 * pipeline do TraceVanta sobe. Como a leitura é feita no MOMENTO DO EVENTO, a
 * ordem de inicialização dos beans não importa.
 */
public final class TraceVantaBridge {

    private static volatile Consumer<TraceVantaEvent> sink;
    private static volatile TraceVantaConfig config;

    private TraceVantaBridge() {}

    public static void set(Consumer<TraceVantaEvent> eventSink, TraceVantaConfig cfg) {
        sink = eventSink;
        config = cfg;
    }

    public static void clear() {
        sink = null;
        config = null;
    }

    static Consumer<TraceVantaEvent> sink() {
        return sink;
    }

    static TraceVantaConfig config() {
        return config != null ? config : TraceVantaConfig.defaults();
    }
}
