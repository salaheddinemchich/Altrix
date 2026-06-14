package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Payload-size and message-count constraints applied when splitting a 
 * collection of items across multiple Kafka producer requests.
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
        // Kafka producer batch size limits (example values, adjust as needed)
        // Reference: https://kafka.apache.org/documentation/#producerconfigs
        return new PubSubRequestConstraints(10 * 1024 * 1024, 1000, 10 * 1024 * 1024);
    }

    public static PubSubRequestConstraints createForAckRequests() {
        // Kafka consumer ack batch size limits (example values, adjust as needed)
        // Note: Kafka uses offset commits rather than ack IDs like Pub/Sub
        return new PubSubRequestConstraints(524288, 2500, 1024);
    }
}