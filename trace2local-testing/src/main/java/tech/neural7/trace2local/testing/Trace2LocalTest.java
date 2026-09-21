package tech.neural7.trace2local.testing;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Extensão JUnit 5 para asserções sobre a árvore em testes de integração
 * (SPEC §4.3 / §10): sobe o pipeline em memória e o SDK OTel com o processor do
 * Trace2Local; os spans criados pelo teste aparecem no TVEM para o
 * {@link Trace2LocalAssertions}.
 *
 * <pre>{@code
 * @Trace2LocalTest
 * class MyIntegrationTest {
 *     @Test void orderFlow() {
 *         Tracer tracer = Trace2LocalAssertions.tracer();
 *         Span s = tracer.spanBuilder("op").startSpan();
 *         s.end();
 *         Trace2LocalAssertions.awaitLatestExecution(Duration.ofSeconds(5))
 *             .hasNode(NodeKind.UNKNOWN, "op");
 *     }
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@org.junit.jupiter.api.extension.ExtendWith(Trace2LocalTestExtension.class)
public @interface Trace2LocalTest {
}
