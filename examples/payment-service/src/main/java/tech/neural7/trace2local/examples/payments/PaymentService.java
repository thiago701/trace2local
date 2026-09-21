package tech.neural7.trace2local.examples.payments;

import org.springframework.stereotype.Service;
import tech.neural7.trace2local.spring.Trace2Local;

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
    @Trace2Local("ProcessarPagamento")
    public PaymentRepository.SaveResult process(CreatePaymentRequest request) {
        return repository.save(new Payment(request.key(), request.payer(), request.amount(), "PENDING"));
    }

    /** Confirma o Pix (regra: PENDING → CONFIRMED). */
    @Trace2Local("ConfirmarPagamento")
    public Payment confirm(String key) {
        Payment payment = repository.find(key);
        if (payment == null) {
            throw new IllegalArgumentException("pagamento não encontrado: " + key);
        }
        return repository.confirm(key);
    }

    /** Notifica o pagador — passo de integração externa (simulado). */
    @Trace2Local("NotificarPagador")
    public void notifyPayer(Payment payment) {
        try {
            Thread.sleep(40); // latência simulada da integração
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Trace2Local("BuscarPagamento")
    public Payment find(String key) {
        return repository.find(key);
    }
}
