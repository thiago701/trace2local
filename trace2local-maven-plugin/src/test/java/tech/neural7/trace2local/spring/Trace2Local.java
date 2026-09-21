package tech.neural7.trace2local.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stub da anotação @Trace2Local para os fixtures do scanner (mesmo FQN da lib). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface Trace2Local {
    String value();
}
