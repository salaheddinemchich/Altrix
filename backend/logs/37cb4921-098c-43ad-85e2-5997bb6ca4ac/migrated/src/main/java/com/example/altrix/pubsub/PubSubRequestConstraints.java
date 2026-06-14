package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Payload-size and message-count constraints applied when splitting a 
 * collection of items across multiple Kafka requests.
 */
@Getter
@RequiredArgsConstructor
public class PubSubRequestConstraints {
    /**
     * Maximum total payload size in bytes per request.
     */
    private final int maxRequestBytes;
    /**
     * Maximum number of items per request.
     */
    private final int maxItemsPerRequest;
    /**
     * Per-item payload cap in bytes.
     */
    private final int maxItemBytes;

    public static PubSubRequestConstraints createForPublishRequests() {
        // Kafka has no strict per-request size limit like Pub/Sub, but for consistency:
        // - Typical batch size for Kafka is around 1MB to 5MB for performance.
        // - Message count: Kafka doesn't enforce a hard limit per request, aiming for ~1000 messages.
        // - Per message size: Kafka's max is theoretically up to ~1MB (practical limit ~900KB due to overhead).
        return new PubSubRequestConstraints(5 * 1024 * 1024, 1000, 900 * 1024);
    }

    public static PubSubRequestConstraints createForAckRequests() {
        // Kafka acknowledgment is per consumer group offset commit, not request-sized.
        // Thus, we focus on a reasonable batch size for commits (not directly comparable to Pub/Sub acks).
        // - Max request bytes: Irrelevant in this context, set to a high value for flexibility.
        // - Max items per request: Aligns with the number of messages to process before committing.
        // - Per item bytes: Not directly applicable, set for consistency with the concept of message handling.
        return new PubSubRequestConstraints(Integer.MAX_VALUE, 2500, 1024);
    }
}