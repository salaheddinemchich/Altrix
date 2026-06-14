package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
@Slf4j
@RequiredArgsConstructor
public class AcknowledgeMessagesTask extends RetryTask<Void> {
    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String fullSubscriptionName;
    private final String[] ackIds; // Note: Kafka doesn't directly map to ackIds, this is a placeholder for demonstration

    public Void call() throws Exception {
        try {
            log.debug("Acknowledging {} message(s) on {}", ackIds.length, fullSubscriptionName);
            // Kafka commit example (assuming ackIds are translated to Kafka offsets)
            Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
            // Example translation (real logic depends on how ackIds relate to Kafka offsets)
            for (String ackId : ackIds) {
                // TODO altrix: Implement actual offset translation logic here
                // For demonstration, assuming a direct (and unrealistic) mapping
                TopicPartition tp = new TopicPartition(fullSubscriptionName, 0); // Topic, Partition
                OffsetAndMetadata offset = new OffsetAndMetadata(Long.parseLong(ackId)); // Simplistic example
                offsetsToCommit.put(tp, offset);
            }
            kafkaConsumer.commitSync(offsetsToCommit);
            return null; // Changed return type to Void, so return null
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}