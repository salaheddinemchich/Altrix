package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
@RequiredArgsConstructor
@Slf4j
public class AcknowledgeMessagesTask extends RetryTask<Void> {

    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String fullSubscriptionName;
    private final long[] offsetsToAcknowledge;

    public Void call() throws Exception {
        try {
            log.debug("Acknowledging {} message(s) on {}", offsetsToAcknowledge.length, fullSubscriptionName);
            // Map subscription to topic-partition (assuming 1 partition for simplicity)
            TopicPartition topicPartition = new TopicPartition(fullSubscriptionName, 0);
            OffsetAndMetadata[] offsets = new OffsetAndMetadata[offsetsToAcknowledge.length];
            for (int i = 0; i < offsetsToAcknowledge.length; i++) {
                offsets[i] = new OffsetAndMetadata(offsetsToAcknowledge[i]);
            }
            kafkaConsumer.commitSync(java.util.Collections.singletonMap(topicPartition, offsets));
            return null; // Changed return type to Void, so return null
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}