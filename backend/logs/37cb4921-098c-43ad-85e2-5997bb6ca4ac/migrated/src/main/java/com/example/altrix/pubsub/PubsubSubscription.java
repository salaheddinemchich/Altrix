package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Pub/Sub subscription.
 *
 * <p>{@link #type} is {@link PubsubSubscriptionType#PULL} for poll-based
 * consumers (used here) or {@link PubsubSubscriptionType#PUSH} for HTTP push
 * delivery from GCP.
 */
@Getter
@RequiredArgsConstructor
public class PubsubSubscription {

    private final String projectId;
    private final String subscriptionName;
    private final PubsubSubscriptionType type;
    private final int ackDeadline;

    public String getFullSubscriptionName() {
        return "projects/" + projectId + "/subscriptions/" + subscriptionName;
    }
}
