package tech.neural7.tracevanta.examples.payments;

import org.springframework.stereotype.Service;
import tech.neural7.tracevanta.spring.TraceVanta;

/**
 * Regras de negócio de pagamentos — cada método vira um nó BUSINESS na árvore
 * e um passo da narrativa (StoryService).
 */
@Service
public class PaymentService {

    private final PaymentRepository repository;

    public PaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    /** Cria o Pix — a escrita condicional no repositório é a guarda de idempotência. */
    @TraceVanta("ProcessarPagamento")
    public PaymentRepository.SaveResult process(CreatePaymentRequest request) {
        return repository.save(new Payment(request.key(), request.payer(), request.amount(), "PENDING"));
    }

    /** Confirma o Pix (regra: PENDING → CONFIRMED). */
    @TraceVanta("ConfirmarPagamento")
    public Payment confirm(String key) {
        Payment payment = repository.find(key);
        if (payment == null) {
            throw new IllegalArgumentException("pagamento não encontrado: " + key);
        }
        return repository.confirm(key);
    }

    /** Notifica o pagador — passo de integração externa (simulado). */
    @TraceVanta("NotificarPagador")
    public void notifyPayer(Payment payment) {
        try {
            Thread.sleep(40); // latência simulada da integração
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @TraceVanta("BuscarPagamento")
    public Payment find(String key) {
        return repository.find(key);
    }
}
