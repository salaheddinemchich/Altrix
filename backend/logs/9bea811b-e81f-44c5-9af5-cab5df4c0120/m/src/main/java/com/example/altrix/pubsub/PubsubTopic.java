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
    private final String projectId;
    private final String topicName;

    public String getFullTopicName() {
        // Kafka topic names do not include the project ID in the name.
        // The projectId is typically handled at the Kafka cluster configuration level.
        return topicName;
    }
}