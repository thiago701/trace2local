package tech.neural7.trace2local.examples.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo de pagamentos Pix — domínio DIFERENTE dos exemplos de pedidos, para
 * validar que o Trace2Local (e a narrativa de negócio) é domínio-agnóstico.
 *
 * <p>Fluxos: criar Pix (guarda de idempotência), duplicado recusado sem efeito
 * colateral e confirmação (update com read-back — before+after EXACT).
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
