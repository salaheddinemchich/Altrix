package com.example.altrix.pubsub;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

/**
 * Internal representation of a message to be sent through Pub/Sub —
 * decoupled from the Google client model so callers don't import GCP types.
 */
@Getter
@AllArgsConstructor
public class AltrixPubsubMessage {

    private final Map<String, String> attributes;
    private final byte[] message;
    private final String orderingKey;

    public AltrixPubsubMessage(Map<String, String> attributes, byte[] message) {
        this(attributes, message, null);
    }
}
