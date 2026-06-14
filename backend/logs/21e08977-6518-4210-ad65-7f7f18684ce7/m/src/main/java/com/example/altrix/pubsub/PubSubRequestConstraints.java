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
        // Kafka has no strict per-request limits like Pub/Sub, but let's use reasonable defaults
        // inspired by typical Kafka producer batch settings (e.g., batch.size, max.request.size)
        return new PubSubRequestConstraints(50 * 1024 * 1024, 1000, 10 * 1024 * 1024);
    }

    public static PubSubRequestConstraints createForAckRequests() {
        // Kafka uses offset commits rather than ack IDs, so we focus on a reasonable batch size
        // for commits (no direct equivalent to Pub/Sub's ack request limits)
        return new PubSubRequestConstraints(524288, 2500, 1024);
    }
}