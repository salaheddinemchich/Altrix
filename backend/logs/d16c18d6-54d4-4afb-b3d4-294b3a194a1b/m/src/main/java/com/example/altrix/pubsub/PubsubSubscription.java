package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Kafka topic subscription (formerly Pub/Sub subscription).
 * 
 * <p>Note: The original Pub/Sub subscription type (PULL/PUSH) is no longer directly applicable in Kafka.
 *         This class now represents a basic Kafka topic subscription identifier.
 */
@Getter
@RequiredArgsConstructor
public class PubsubSubscription {
    private final String topicName; // Renamed from subscriptionName to reflect Kafka topic
    private final String groupId;    // Added to represent Kafka consumer group ID
    private final int ackTimeout;   // Renamed from ackDeadline to align with Kafka's ack timeout concept

    /**
     * Returns the full Kafka topic name (simple topic name, as Kafka doesn't use project IDs).
     */
    public String getFullTopicName() {
        return topicName; // Simplified, as Kafka topics don't require project prefixes
    }
}