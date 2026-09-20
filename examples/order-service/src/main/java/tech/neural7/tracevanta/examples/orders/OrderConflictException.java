package tech.neural7.tracevanta.examples.orders;

/** JC-2: falha de condição no DynamoDB, visível na árvore com o delta e a exceção. */
public class OrderConflictException extends RuntimeException {

    private final String orderId;

    public OrderConflictException(String orderId, Throwable cause) {
        super("pedido " + orderId + " não está PENDING — condição violada", cause);
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}
