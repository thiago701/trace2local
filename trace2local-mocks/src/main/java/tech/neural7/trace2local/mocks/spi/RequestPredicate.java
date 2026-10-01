package tech.neural7.trace2local.mocks.spi;

import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.model.MockRequest;
import tech.neural7.trace2local.mocks.model.RequestMatcher;
import tech.neural7.trace2local.mocks.model.Stub;

import java.util.function.Predicate;

/**
 * Condição que liga/desliga uma transformação ({@code transforms.x.predicate=y},
 * com {@code negate} opcional) — idêntico aos predicates do Kafka Connect.
 */
public interface RequestPredicate extends MockPlugin {

    @Override
    default PluginType type() {
        return PluginType.PREDICATE;
    }

    Condition configure(MockConfig config);

    /**
     * Tradução para destinos estáticos. Padrão: não suportado (o worker recusa o binding
     * com mensagem clara em vez de publicar algo que se comportaria diferente).
     */
    default StaticForm staticForm(MockConfig config) {
        return new StaticForm.Unsupported(name() + " depende de estado por requisição — use sink=embedded");
    }

    @FunctionalInterface
    interface Condition {
        boolean test(MockRequest request, CallContext context);
    }

    /** Como o predicado vira algo publicável num destino estático. */
    sealed interface StaticForm {
        /** Avaliado contra o stub (método/caminho): aplica ou não a transformação no stub inteiro. */
        record OnStub(Predicate<Stub> test) implements StaticForm {}

        /** Gera um stub ADICIONAL, mais prioritário, com esta restrição de requisição. */
        record ExtraConstraint(RequestMatcher.Constraint constraint) implements StaticForm {}

        record Unsupported(String reason) implements StaticForm {}
    }
}
