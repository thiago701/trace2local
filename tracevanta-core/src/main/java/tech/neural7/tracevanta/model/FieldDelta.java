package tech.neural7.tracevanta.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * Um campo que mudou entre {@code before} e {@code after}.
 * {@code before} é {@link NullNode} quando o campo foi adicionado; {@code after} é
 * {@link NullNode} quando o campo foi removido.
 *
 * @param path   caminho do campo no documento JSON (ex.: {@code customer.name})
 * @param before valor antes da mutação
 * @param after  valor depois da mutação
 */
public record FieldDelta(String path, JsonNode before, JsonNode after) {}
