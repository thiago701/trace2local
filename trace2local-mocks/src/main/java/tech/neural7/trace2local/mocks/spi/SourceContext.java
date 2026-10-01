package tech.neural7.trace2local.mocks.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** O que o worker oferece a uma fonte de stubs. */
public interface SourceContext {

    /** A API que o binding substitui ({@code target} + nome lógico). */
    ApiTarget target();

    /** Trocas HTTP observadas nos traces para o alvo (mais recentes primeiro), já redigidas. */
    List<ObservedExchange> observed();

    /**
     * Resolve um caminho relativo dentro do diretório de dados de mocks
     * ({@code TRACE2LOCAL_MOCKS_DIR}). Vazio se o diretório não estiver configurado
     * ou se o caminho escapar dele ({@code ..}, absoluto, link) — sem leitura arbitrária de disco.
     */
    Optional<Path> resolve(String relative);
}
