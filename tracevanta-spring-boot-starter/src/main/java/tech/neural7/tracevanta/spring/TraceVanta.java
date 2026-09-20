package tech.neural7.tracevanta.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um método de negócio para aparecer na árvore como nó BUSINESS
 * (SPEC §1.5 / E5 — "método de negócio anotado"). Sem agente e sem bytecode:
 * o starter aplica um aspecto do Spring AOP em build/runtime comum, compatível
 * com GraalVM Native Image.
 *
 * <pre>{@code
 * @TraceVanta("CreateOrder")
 * public Order create(OrderRequest request) { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TraceVanta {

    /** Rótulo do nó; vazio = nome do método. */
    String value() default "";
}
