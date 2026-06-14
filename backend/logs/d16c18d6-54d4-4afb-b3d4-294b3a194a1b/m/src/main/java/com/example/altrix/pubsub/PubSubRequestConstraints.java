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
        // Kafka's practical limits (may vary by cluster config, these are reasonable defaults)
        // Typical max request size for Kafka is around 1MB to 5MB, and max messages per request can be high
        return new PubSubRequestConstraints(5 * 1024 * 1024, 10000, 1 * 1024 * 1024);
    }

    public static PubSubRequestConstraints createForFetchRequests() {
        // Kafka consumer fetch defaults are often in the range of a few MB to tens of MB
        return new PubSubRequestConstraints(10 * 1024 * 1024, 10000, 1 * 1024 * 1024);
    }
}