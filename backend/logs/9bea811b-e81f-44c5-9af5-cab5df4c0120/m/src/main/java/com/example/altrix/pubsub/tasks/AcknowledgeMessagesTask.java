package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.RetryTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.Map;

import java.util.List;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
@Slf4j
@RequiredArgsConstructor
public class AcknowledgeMessagesTask extends RetryTask<Void> {
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String fullSubscriptionName;
    private final List<Long> ackIds; // Assuming ackIds are now passed as a list of offsets

    public Void call() throws Exception {
        try {
            log.debug("Acknowledging {} message(s) on {}", ackIds.size(), fullSubscriptionName);
            Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
            // Assuming fullSubscriptionName is now a topic name and ackIds are offsets
            TopicPartition tp = new TopicPartition(fullSubscriptionName, 0); // Partition 0 assumed, adjust if needed
            for (Long ackId : ackIds) {
                offsetsToCommit.put(tp, new OffsetAndMetadata(ackId + 1)); // +1 because Kafka is 0-offset based but often acknowledged as next to consume
            }
            kafkaConsumer.commitSync(offsetsToCommit);
            return null; // Changed return type to Void, so return null
        } catch (Exception e) {
            throw new RuntimeException(e); // Simplified error handling
        }
    }
}