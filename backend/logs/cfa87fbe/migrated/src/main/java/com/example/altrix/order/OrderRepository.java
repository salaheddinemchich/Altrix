package com.example.altrix.order;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory repository. Swap for a JPA-backed adapter when wiring real persistence —
 * the contract here is the only thing the service layer depends on.
 */
@ApplicationScoped
public class OrderRepository {

    private final ConcurrentMap<String, Order> orders = new ConcurrentHashMap<>();

    public Order save(Order order) {
        orders.put(order.getId(), order);
        return order;
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public Collection<Order> findAll() {
        return orders.values();
    }

    public boolean delete(String id) {
        return orders.remove(id) != null;
    }
}
