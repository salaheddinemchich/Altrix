package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Kafka topic subscription (formerly Pub/Sub subscription).
 * 
 * <p>Note: Kafka does not have a direct equivalent to Pub/Sub subscriptions. 
 *         This class is retained for structural consistency, but its usage 
 *         may need adjustment based on the Kafka consumer group configuration.
 */
@Getter
public class PubsubSubscription {
    private final String topicName; // Renamed from subscriptionName for Kafka context
    private final String groupId;   // Added to reflect Kafka consumer group
    private final int ackTimeout;   // Renamed from ackDeadline for clarity in Kafka context

    /**
     * Constructs a PubsubSubscription with the given topic name and consumer group ID.
     * 
     * @param topicName The name of the Kafka topic.
     * @param groupId   The ID of the Kafka consumer group.
     * @param ackTimeout The timeout in seconds for acknowledging messages.
     */
    public PubsubSubscription(String topicName, String groupId, int ackTimeout) {
        this.topicName = topicName;
        this.groupId = groupId;
        this.ackTimeout = ackTimeout;
    }

    /**
     * Returns a string representation of the subscription for logging/debugging.
     * 
     * @return A string in the format "topic/<topicName> (group=<groupId>)".
     */
    public String getSubscriptionInfo() {
        return "topic/" + topicName + " (group=" + groupId + ")";
    }
}