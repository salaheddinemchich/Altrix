package com.example.payments;

/**
 * Topic/subscription names the payments domain owns. Mirrors
 * {@code com.example.orders.PubSubConfig} — one anchor per bounded context
 * so the Kafka migration can update each independently.
 */
public final class PubSubConfig {

    /** Payments' own consumer group on the orders.created topic — a second
     *  subscription alongside orders' own {@code orders.created.processor}. */
    public static final String ORDERS_CREATED_SUBSCRIPTION = "orders.created.payments";

    /** Topic published once a payment is taken. */
    public static final String PAYMENTS_COMPLETED_TOPIC = "payments.completed";

    /** Topic published when a completed payment is refunded. */
    public static final String PAYMENTS_REFUNDED_TOPIC = "payments.refunded";

    private PubSubConfig() {
    }
}