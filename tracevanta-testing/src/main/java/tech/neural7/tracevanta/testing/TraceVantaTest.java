package tech.neural7.tracevanta.testing;

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
 * TraceVanta; os spans criados pelo teste aparecem no TVEM para o
 * {@link TraceVantaAssertions}.
 *
 * <pre>{@code
 * @TraceVantaTest
 * class MyIntegrationTest {
 *     @Test void orderFlow() {
 *         Tracer tracer = TraceVantaAssertions.tracer();
 *         Span s = tracer.spanBuilder("op").startSpan();
 *         s.end();
 *         TraceVantaAssertions.awaitLatestExecution(Duration.ofSeconds(5))
 *             .hasNode(NodeKind.UNKNOWN, "op");
 *     }
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@org.junit.jupiter.api.extension.ExtendWith(TraceVantaTestExtension.class)
public @interface TraceVantaTest {
}
