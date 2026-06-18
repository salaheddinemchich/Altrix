package com.example.payments;

/**
 * Payment raised in response to an order — pipe-delimited wire format,
 * matching the convention {@code com.example.orders.Order} already uses in
 * this fixture.
 */
public record Payment(String id, String orderId, double amount, String status) {

    public String toMessage() {
        return id + "|" + orderId + "|" + amount + "|" + status;
    }

    public static Payment fromMessage(String message) {
        String[] parts = message.split("\\|");
        return new Payment(parts[0], parts[1], Double.parseDouble(parts[2]), parts[3]);
    }
}
