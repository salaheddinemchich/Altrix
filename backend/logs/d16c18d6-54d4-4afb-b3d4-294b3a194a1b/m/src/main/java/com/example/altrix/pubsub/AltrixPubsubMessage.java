package com.example.altrix.pubsub;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.Map;

/**
 * Internal representation of a message to be sent through Kafka — 
 * decoupled from the Kafka client model so callers don't import Kafka types.
 */
@Getter
@AllArgsConstructor
public class AltrixPubsubMessage {
    private final Map<String, String> headers; // Renamed from attributes to align with Kafka terminology
    private final String message; // Changed from byte[] to String, assuming UTF-8 encoding for Kafka
    private final String orderingKey;

    public AltrixPubsubMessage(Map<String, String> headers, String message) {
        this(headers, message, null);
    }
}