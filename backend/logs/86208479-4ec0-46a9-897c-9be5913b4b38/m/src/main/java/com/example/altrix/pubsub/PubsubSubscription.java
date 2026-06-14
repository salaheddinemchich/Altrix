package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Kafka topic subscription (formerly Pub/Sub subscription).
 * 
 * <p>{@link #type} is now obsolete as Kafka does not differentiate between pull and push in the same way.
 * For Kafka, consumers always pull messages. The type is retained for potential future use or logging.
 */
@Getter
@RequiredArgsConstructor
public class PubsubSubscription {
    private final String topicName; // Renamed from projectId + subscriptionName for Kafka topic simplicity
    private final PubsubSubscriptionType type; // Retained for compatibility, though Kafka doesn't use this distinction
    private final int ackDeadline; // Retained, though its meaning may differ in a Kafka context (e.g., session timeout)

    /**
     * Returns the Kafka topic name (simplified from the original GCP Pub/Sub full subscription name).
     * 
     * @return Simplified topic name for Kafka (e.g., "my-topic" instead of "projects/X/subscriptions/Y")
     */
    public String getTopicName() {
        return topicName; // Directly returns the simplified topic name for Kafka
    }

    // TODO altrix: AckDeadline in Kafka context might need reevaluation for its usage (e.g., consumer session timeout)
}