package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Kafka topic subscription (formerly Pub/Sub subscription).
 * 
 * <p>{@link #type} is {@link PubsubSubscriptionType#PULL} for poll-based 
 * consumers (used here) or {@link PubsubSubscriptionType#PUSH} for HTTP push 
 * delivery from GCP (NOT directly applicable to Kafka, TODO altrix: 
 * implement custom push logic or use Kafka's built-in consumer groups).
 */
@Getter
@RequiredArgsConstructor
public class PubsubSubscription {
    private final String topicName; // Renamed from projectId + subscriptionName for Kafka topic context
    private final PubsubSubscriptionType type; // NOTE: PUSH type requires custom Kafka implementation
    private final int ackDeadline; // TODO altrix: Map to Kafka's commit strategy (e.g., auto-commit or manual commit)

    /**
     * Returns the full Kafka topic name (simplified from GCP's full subscription name).
     * 
     * @return Topic name in the format "topic_name" (not "projects/.../subscriptions/...").
     */
    public String getFullSubscriptionName() {
        // Simplified for Kafka, as topic names don't follow the GCP projects/subscriptions pattern
        return topicName;
    }
}