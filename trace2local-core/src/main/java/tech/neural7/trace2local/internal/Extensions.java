package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.spi.Trace2LocalExtension;

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

    private static final List<Trace2LocalExtension> LOADED = load();

    private Extensions() {}

    public static List<Trace2LocalExtension> all() {
        return LOADED;
    }

    private static List<Trace2LocalExtension> load() {
        List<Trace2LocalExtension> extensions = new ArrayList<>();
        try {
            for (Trace2LocalExtension extension : ServiceLoader.load(Trace2LocalExtension.class)) {
                extensions.add(extension);
            }
        } catch (Throwable t) {
            // degradação preferida à falha: log no nível debug é responsabilidade do chamador
        }
        extensions.sort(Comparator.comparingInt(Trace2LocalExtension::order));
        return List.copyOf(extensions);
    }
}
