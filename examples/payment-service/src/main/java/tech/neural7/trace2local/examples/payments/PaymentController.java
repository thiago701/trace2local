package tech.neural7.trace2local.examples.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

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
        // log de negócio: carrega trace_id/span_id (OTel) e dd.* (Datadog) via MDC
        log.info("Pix {} {}", payment.key(), result.duplicated() ? "DUPLICADO recusado pela guarda" : "criado");
        return ResponseEntity.status(result.duplicated() ? 200 : 201).body(Map.of(
                "key", payment.key(),
                "status", payment.status(),
                "duplicated", result.duplicated()));
    }

    @PostMapping("/pix/{key}/confirm")
    public Map<String, Object> confirm(@PathVariable String key) {
        Payment payment = service.confirm(key);
        log.info("Pix {} confirmado", key);
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
