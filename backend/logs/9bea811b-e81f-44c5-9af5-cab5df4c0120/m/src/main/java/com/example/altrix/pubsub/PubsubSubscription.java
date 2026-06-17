package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Kafka Topic Subscription (formerly Pub/Sub subscription).
 * 
 * <p>{@link #type} is no longer applicable as Kafka does not differentiate 
 * between pull and push subscriptions in the same way. This class now serves 
 * as a basic identifier for a Kafka topic subscription.
 */
@Getter
@RequiredArgsConstructor
public class PubsubSubscription {
    private final String topicName; // Renamed from subscriptionName
    private final String groupId;   // Added to represent Kafka consumer group
    private final int maxPollRecords; // Added, relevant for Kafka consumer config
    // private final PubsubSubscriptionType type; // Removed, not applicable in Kafka
    // private final int ackDeadline; // Removed, ack mechanism differs in Kafka

    /**
     * Returns the full topic name (formerly subscription name) for identification.
     */
    public String getFullTopicName() {
        return topicName; // Simplified, as Kafka topic names are not project-based
    }
}