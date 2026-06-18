package com.example.payments;

/**
 * Payments' own view of the order-created wire message — duplicated from
 * {@code com.example.orders.Order} on purpose. In a real distributed system
 * Orders and Payments would be separate deployables with no compile-time
 * dependency on each other's DTOs; each bounded context owns its side of
 * the message contract independently.
 */
public record OrderCreatedEvent(String id, String product, int quantity) {

    public static OrderCreatedEvent fromMessage(String message) {
        String[] parts = message.split("\\|");
        return new OrderCreatedEvent(parts[0], parts[1], Integer.parseInt(parts[2]));
    }
}
