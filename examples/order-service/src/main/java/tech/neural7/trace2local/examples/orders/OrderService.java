package tech.neural7.trace2local.examples.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tech.neural7.trace2local.spring.Trace2Local;

import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final SnsClient sns;
    private final String topicArn;

    public OrderService(OrderRepository repository, SnsClient sns,
                        @Value("${orders.sns-topic-arn}") String topicArn) {
        this.repository = repository;
        this.sns = sns;
        this.topicArn = topicArn;
    }

    /** JC-1: grava no DynamoDB e publica no SNS — cada passo vira um nó na árvore. */
    @Trace2Local("CreateOrder")
    public Order create(CreateOrderRequest request) {
        String orderId = "ORDER#" + UUID.randomUUID().toString().substring(0, 8);
        Order order = new Order(orderId, request.customerId(), request.total(), "PENDING");
        repository.save(order);
        sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message("{\"orderId\":\"" + orderId + "\"}")
                .build());
        return order;
    }

    @Trace2Local("FindOrder")
    public Order find(String orderId) {
        return repository.findById(orderId).orElse(null);
    }

    /** JC-2: quando a condição falha, a execução aparece VERMELHA com a exceção. */
    @Trace2Local("ConfirmOrder")
    public Order confirm(String orderId) {
        Order confirmed = repository.confirm(orderId);
        // JC-3: o confirm também publica no SNS — o consumidor da fila billing-queue
        // pega o evento, continua o MESMO trace (AWSTraceHeader) e marca o pedido BILLED
        sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message("{\"orderId\":\"" + confirmed.orderId() + "\",\"event\":\"confirmed\"}")
                .build());
        return confirmed;
    }

    /** Chamado pelo consumidor da fila, dentro do contexto do trace propagado. */
    @Trace2Local("BillOrder")
    public void bill(String orderId) {
        Order order = repository.findById(orderId).orElse(null);
        if (order != null && "CONFIRMED".equals(order.status())) {
            repository.markBilled(orderId);
        }
    }
}
