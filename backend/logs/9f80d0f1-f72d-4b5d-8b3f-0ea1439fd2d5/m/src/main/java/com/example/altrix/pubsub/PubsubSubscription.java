package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Kafka topic subscription (formerly Pub/Sub subscription).
 * 
 * <p>Note: Kafka does not have a direct equivalent to Pub/Sub subscriptions. 
 *         This class is adapted for Kafka topic configuration.
 */
@Getter
@RequiredArgsConstructor
public class PubsubSubscription {
    private final String topicName; // Renamed from subscriptionName for Kafka context
    private final String groupId;   // Added for Kafka consumer group ID
    private final int ackTimeout;  // Renamed from ackDeadline, units may differ

    /**
     * Returns the full Kafka topic name (simple name, as Kafka doesn't use project IDs).
     */
    public String getFullTopicName() {
        return topicName; // Simplified for Kafka, no project ID needed
    }
}