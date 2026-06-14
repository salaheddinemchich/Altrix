package com.example.altrix.pubsub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Identity of a Kafka topic — combines project id and topic name into the
 * fully-qualified Kafka topic name.
 */
@Getter
@RequiredArgsConstructor
public class PubsubTopic {
    private final String projectId; // Not used in Kafka, retained for potential logging/debugging
    private final String topicName;

    /**
     * Returns the Kafka topic name (same as topicName, as Kafka doesn't require project prefix).
     */
    public String getFullTopicName() {
        return topicName; // Kafka topics don't include project IDs in their names
    }
}