package com.example.altrix.payment;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@ApplicationScoped
public class PaymentRepository {

    private final ConcurrentMap<String, Payment> payments = new ConcurrentHashMap<>();

    public Payment save(Payment payment) {
        payments.put(payment.getId(), payment);
        return payment;
    }

    public Optional<Payment> findById(String id) {
        return Optional.ofNullable(payments.get(id));
    }

    public List<Payment> findByOrderId(String orderId) {
        return payments.values().stream()
                .filter(p -> orderId.equals(p.getOrderId()))
                .toList();
    }

    public Collection<Payment> findAll() {
        return payments.values();
    }
}
