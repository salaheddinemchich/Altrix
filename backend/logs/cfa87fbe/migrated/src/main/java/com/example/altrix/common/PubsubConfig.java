package com.example.altrix.common;

import com.example.altrix.pubsub.PubsubSubscription;
import com.example.altrix.pubsub.PubsubSubscriptionType;
import com.example.altrix.pubsub.PubsubTopic;

/**
 * Topic and subscription names used across the application. Centralised so 
 * the migration agent has a single anchor to update when porting to Kafka.
 */
public final class PubsubConfig {
    public static final String PROJECT_ID = System.getProperty("gcp.project.id", System.getenv().getOrDefault("GCP_PROJECT_ID", "altrix-local"));
    public static final PubsubTopic ORDERS_CREATED = new PubsubTopic(PROJECT_ID, "orders.created");
    public static final PubsubTopic PAYMENTS_COMPLETED = new PubsubTopic(PROJECT_ID, "payments.completed");
    public static final PubsubSubscription ORDERS_CREATED_PAYMENT_SUB = new PubsubSubscription(PROJECT_ID, "orders.created.payment-svc", PubsubSubscriptionType.PULL, 30);

    private PubsubConfig() {}
}