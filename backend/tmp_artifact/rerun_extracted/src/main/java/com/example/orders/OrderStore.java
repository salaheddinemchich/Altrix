package com.example.orders;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory order status tracker. Real persistence is out of scope for this
 * fixture — the messaging mechanics are the point.
 */
@Component
public class OrderStore {

    public enum Status { CREATED, PAID }

    private final Map<String, Status> statuses = new ConcurrentHashMap<>();

    public void markCreated(String orderId) {
        statuses.put(orderId, Status.CREATED);
    }

    public void markPaid(String orderId) {
        statuses.put(orderId, Status.PAID);
    }

    public Status statusOf(String orderId) {
        return statuses.get(orderId);
    }
}
