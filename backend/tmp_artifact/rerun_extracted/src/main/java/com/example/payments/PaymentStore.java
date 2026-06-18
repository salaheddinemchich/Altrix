package com.example.payments;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory payment ledger keyed by order id. Real persistence is out of
 * scope for this fixture — the messaging mechanics are the point.
 */
@Component
public class PaymentStore {

    private final Map<String, Payment> byOrderId = new ConcurrentHashMap<>();

    public void save(Payment payment) {
        byOrderId.put(payment.orderId(), payment);
    }

    public Payment findByOrderId(String orderId) {
        return byOrderId.get(orderId);
    }
}
