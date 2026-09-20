package tech.neural7.tracevanta.internal;

import tech.neural7.tracevanta.spi.TraceVantaExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Carrega as extensões da SPI via {@link ServiceLoader} (AOT-safe, ADR-005/§4.7),
 * ordenadas por {@code order()}. Toda exceção de extensão é engolida aqui —
 * uma extensão quebrada nunca derruba a aplicação do dev (SPEC §7.3).
 */
public final class Extensions {

    private static final List<TraceVantaExtension> LOADED = load();

    private Extensions() {}

    public static List<TraceVantaExtension> all() {
        return LOADED;
    }

    private static List<TraceVantaExtension> load() {
        List<TraceVantaExtension> extensions = new ArrayList<>();
        try {
            for (TraceVantaExtension extension : ServiceLoader.load(TraceVantaExtension.class)) {
                extensions.add(extension);
            }
        } catch (Throwable t) {
            // degradação preferida à falha: log no nível debug é responsabilidade do chamador
        }
        extensions.sort(Comparator.comparingInt(TraceVantaExtension::order));
        return List.copyOf(extensions);
    }
}
