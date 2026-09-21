package tech.neural7.tracevanta.examples.payments;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/pix")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreatePaymentRequest request) {
        PaymentRepository.SaveResult result = service.process(request);
        Payment payment = result.payment();
        // passo de integração (nó BUSINESS irmão na árvore)
        service.notifyPayer(payment);
        return ResponseEntity.status(result.duplicated() ? 200 : 201).body(Map.of(
                "key", payment.key(),
                "status", payment.status(),
                "duplicated", result.duplicated()));
    }

    @PostMapping("/pix/{key}/confirm")
    public Map<String, Object> confirm(@PathVariable String key) {
        Payment payment = service.confirm(key);
        return Map.of("key", payment.key(), "status", payment.status());
    }

    @GetMapping("/pix/{key}")
    public Map<String, Object> find(@PathVariable String key) {
        Payment payment = service.find(key);
        return payment == null
                ? Map.of("found", false)
                : Map.of("key", payment.key(), "payer", payment.payer(),
                        "amount", payment.amount().toPlainString(), "status", payment.status());
    }
}
