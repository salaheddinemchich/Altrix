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
        // inspired by common Kafka producer batch settings (e.g., batch.size up to 16MB, 
        // but keeping under to ensure headroom for headers and variances across brokers)
        return new PubSubRequestConstraints(8 * 1024 * 1024, 1000, 8 * 1024 * 1024);
    }

    public static PubSubRequestConstraints createForAckRequests() {
        // Kafka's acks=all with batch sizes are more flexible; using a high item count
        // with a conservative per-item limit to avoid overly large batches
        return new PubSubRequestConstraints(262144, 2500, 1024);
    }
}