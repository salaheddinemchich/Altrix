package com.example.orders;

/**
 * Pub/Sub topic + subscription names used across the app.  A single anchor
 * the migration can update when porting to Kafka topics / consumer groups.
 */
public final class PubSubConfig {

    /** Topic orders are published to. */
    public static final String ORDERS_TOPIC = "orders.created";

    /** Subscription the order processor pulls from. */
    public static final String ORDERS_SUBSCRIPTION = "orders.created.processor";

    /** Subscription orders uses to react to payment completion (payments domain). */
    public static final String PAYMENTS_COMPLETED_SUBSCRIPTION = "payments.completed.orders";

    private PubSubConfig() {
    }
}