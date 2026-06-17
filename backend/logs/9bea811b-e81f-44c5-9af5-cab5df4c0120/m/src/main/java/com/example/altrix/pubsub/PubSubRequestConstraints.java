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
        // Kafka has no strict per-request size limit like Pub/Sub, but for consistency
        // and to avoid overly large requests, we'll use a reasonable default (e.g., 5MB).
        return new PubSubRequestConstraints(5 * 1024 * 1024, 1000, 5 * 1024 * 1024);
    }

    public static PubSubRequestConstraints createForAckRequests() {
        // Kafka doesn't have a direct equivalent to Pub/Sub's acknowledge request limits.
        // We'll use a conservative approach for batch sizes (e.g., 256KB per request, 2000 items).
        return new PubSubRequestConstraints(262144, 2000, 1024);
    }
}