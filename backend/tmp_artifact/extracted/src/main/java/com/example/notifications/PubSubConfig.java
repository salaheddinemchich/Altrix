package com.example.notifications;

/**
 * Subscriptions this lightweight notification client consumes. It has no
 * publishing side — it only reacts to events the other domains raise.
 */
public final class PubSubConfig {

    /** Notifications' own consumer group on the payments.completed topic. */
    public static final String PAYMENTS_COMPLETED_SUBSCRIPTION = "payments.completed.notifications";

    /** Notifications' own consumer group on the payments.refunded topic. */
    public static final String PAYMENTS_REFUNDED_SUBSCRIPTION = "payments.refunded.notifications";

    private PubSubConfig() {
    }
}