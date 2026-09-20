package tech.neural7.tracevanta.spi;

import java.util.function.Consumer;

/**
 * Canal lateral de mutação de dados (ADR-003): carrega o que não cabe num span.
 * Instrumentações de terceiros publicam aqui; o pipeline em memória ou o
 * exportador HTTP do modo Companion consome. Publicar sem sink é um no-op seguro.
 */
public final class DataMutationChannel {

    @SuppressWarnings("rawtypes")
    private static volatile Consumer SINK;

    private DataMutationChannel() {}

    /** Configurado internamente pelo pipeline (Embedded/Station) ou pelo modo Lambda. */
    @SuppressWarnings("unchecked")
    public static void setSink(Consumer<MutationEvent> sink) {
        SINK = sink;
    }

    /** Nunca lança; a captura de delta jamais pode quebrar a aplicação do dev. */
    @SuppressWarnings("unchecked")
    public static void publish(MutationEvent event) {
        Consumer<MutationEvent> sink = SINK;
        if (sink != null) {
            try {
                sink.accept(event);
            } catch (Throwable ignored) {
                // degradação preferida à falha (SPEC §7.3)
            }
        }
    }
}
