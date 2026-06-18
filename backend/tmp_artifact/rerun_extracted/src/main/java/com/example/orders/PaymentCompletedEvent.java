package com.example.orders;

/**
 * Orders' own view of the "payment completed" wire message — duplicated
 * from {@code com.example.payments.Payment} on purpose. In a real
 * distributed system Orders and Payments would be separate deployables
 * with no compile-time dependency on each other's DTOs.
 */
public record PaymentCompletedEvent(String paymentId, String orderId, double amount, String status) {

    public static PaymentCompletedEvent fromMessage(String message) {
        String[] parts = message.split("\\|");
        return new PaymentCompletedEvent(parts[0], parts[1], Double.parseDouble(parts[2]), parts[3]);
    }
}
