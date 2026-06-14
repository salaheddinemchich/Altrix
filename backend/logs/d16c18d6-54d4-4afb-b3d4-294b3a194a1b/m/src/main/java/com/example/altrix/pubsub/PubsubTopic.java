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
        // Kafka topic names do not require the 'projects/' prefix
        return topicName;
    }
}