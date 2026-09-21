package tech.neural7.tracevanta.plugin.fixtures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.neural7.tracevanta.spring.TraceVanta;

/** Fixture de serviço para o scanner (não é código de produção). */
public class SampleService {

    private static final Logger log = LoggerFactory.getLogger(SampleService.class);

    @TraceVanta("ProcessarPagamento")
    public void process() {
        software.amazon.awssdk.services.dynamodb.StubClient.putItem("payments");
        try {
            java.sql.DriverManager.getConnection("jdbc:h2:mem:test");
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("legado");
        log.info("pagamento processado");
    }
}
