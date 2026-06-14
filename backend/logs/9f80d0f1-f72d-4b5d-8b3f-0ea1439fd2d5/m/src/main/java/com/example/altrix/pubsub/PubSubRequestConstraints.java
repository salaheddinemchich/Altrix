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
        // Kafka's practical limits (may vary based on cluster config):
        // 1 MB max message size (including headers), 
        // no strict item count limit, but batch size is typically <1000,
        // and per-item size should be under 1 MB for most use cases.
        return new PubSubRequestConstraints(1024 * 1024, 1000, 1024 * 1024);
    }

    public static PubSubRequestConstraints createForCommitRequests() {
        // Kafka commit requests are lightweight, but batch sizes are typically limited.
        // Assuming a conservative byte limit and common batch sizes.
        return new PubSubRequestConstraints(524288, 2500, 1024);
    }
}