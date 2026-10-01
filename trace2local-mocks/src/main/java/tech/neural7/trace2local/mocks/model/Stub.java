package tech.neural7.trace2local.mocks.model;

import java.util.List;

/**
 * Par requisição→resposta servido pelo mock.
 *
 * @param operation rótulo legível ("POST /v1/score" ou operationId do contrato)
 * @param priority  1 = mais alta (convenção do WireMock); desempate pela especificidade
 * @param origin    de onde veio: {@code openapi:<arquivo>#<op>}, {@code observed:<execução>}, {@code inline}…
 * @param notes     avisos honestos (ex.: "corpo contém valores redigidos")
 * @param passthrough a resposta base é a da API REAL (repasse); as transformações agem sobre ela
 */
public record Stub(String id, String operation, RequestMatcher request, MockResponse response, int priority,
                   String origin, List<String> notes, boolean passthrough) {

    public Stub {
        priority = priority <= 0 ? 5 : priority;
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public Stub(String id, String operation, RequestMatcher request, MockResponse response, int priority,
                String origin, List<String> notes) {
        this(id, operation, request, response, priority, origin, notes, false);
    }

    public Stub withResponse(MockResponse r) {
        return new Stub(id, operation, request, r, priority, origin, notes, passthrough);
    }

    public Stub withRequest(RequestMatcher m) {
        return new Stub(id, operation, m, response, priority, origin, notes, passthrough);
    }

    public Stub withId(String newId, int newPriority) {
        return new Stub(newId, operation, request, response, newPriority, origin, notes, passthrough);
    }
}
