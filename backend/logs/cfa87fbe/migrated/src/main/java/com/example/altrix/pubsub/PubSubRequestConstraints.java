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

    public static PubSubRequestConstraints createForProduceRequests() {
        // Kafka has no strict per-request limits like Pub/Sub, but for consistency:
        // - Max request size is around 1MB (configurable via max.request.size)
        // - No item count limit (configure based on your Kafka cluster's capabilities)
        // - Per-item size is limited by the broker's message.max.bytes (default 100000000)
        return new PubSubRequestConstraints(1024 * 1024, 1000, 100000000);
    }

    public static PubSubRequestConstraints createForFetchRequests() {
        // Kafka's Fetch API has configurable limits (e.g., fetch.message.max.bytes, max.partition.fetch.bytes)
        // For simplicity, use conservative defaults similar to Pub/Sub's ack requests
        return new PubSubRequestConstraints(524288, 2500, 1024);
    }
}