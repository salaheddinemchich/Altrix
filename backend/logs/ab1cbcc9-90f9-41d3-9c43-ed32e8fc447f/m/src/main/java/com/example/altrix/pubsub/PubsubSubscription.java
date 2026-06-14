package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Kafka consumer group (formerly Pub/Sub subscription).
 * 
 * <p>{@link #type} is now irrelevant as Kafka consumer groups are pull-based by nature.
 * The {@link #ackDeadline} has been replaced with Kafka's auto-commit or manual commit strategies.
 */
@Getter
@RequiredArgsConstructor
public class PubsubSubscription {
    private final String groupId; // Renamed from projectId for Kafka context
    private final String subscriptionName; // Retained for naming consistency, now represents a Kafka consumer group ID
    // private final PubsubSubscriptionType type; // Removed, as type distinction (PULL/PUSH) is not applicable in Kafka
    // private final int ackDeadline; // Removed, as ack deadline is handled differently in Kafka (auto-commit or manual commit)

    public String getFullSubscriptionName() {
        // Adjusted to reflect Kafka consumer group naming (no direct equivalent, using a similar format for consistency)
        return "group/" + groupId + "/subscription/" + subscriptionName;
    }
}