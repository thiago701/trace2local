package tech.neural7.tracevanta.examples.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * App de exemplo das jornadas JC-1/JC-2/JC-3 (SPEC §1.5): POST /orders grava no
 * DynamoDB e publica no SNS (LocalStack), com TraceVanta Embedded em
 * {@code localhost:9876/tracevanta} — uma dependência, zero configuração.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
