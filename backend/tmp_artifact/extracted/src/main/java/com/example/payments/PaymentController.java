package com.example.payments;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry points to inspect and refund payments — lets the payments side
 * of the choreography be exercised end to end, same spirit as
 * {@code com.example.orders.OrderController}.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentStore paymentStore;
    private final PaymentPublisher paymentPublisher;

    public PaymentController(PaymentStore paymentStore, PaymentPublisher paymentPublisher) {
        this.paymentStore = paymentStore;
        this.paymentPublisher = paymentPublisher;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Payment> byOrder(@PathVariable String orderId) {
        Payment payment = paymentStore.findByOrderId(orderId);
        if (payment == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(payment);
    }

    @PostMapping("/{orderId}/refund")
    public ResponseEntity<String> refund(@PathVariable String orderId) {
        Payment payment = paymentStore.findByOrderId(orderId);
        if (payment == null) return ResponseEntity.notFound().build();
        Payment refunded = new Payment(payment.id(), payment.orderId(), payment.amount(), "REFUNDED");
        paymentStore.save(refunded);
        paymentPublisher.publishRefunded(refunded);
        return ResponseEntity.ok("refunded " + orderId);
    }
}
