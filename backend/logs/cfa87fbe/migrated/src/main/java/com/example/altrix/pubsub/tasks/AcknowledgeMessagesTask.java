package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.OffsetAndMetadata;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
@Slf4j
@RequiredArgsConstructor
public class AcknowledgeMessagesTask extends RetryTask<Void> {
    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String topic;
    private final long[] offsets;

    public Void call() throws Exception {
        try {
            log.debug("Acknowledging {} message(s) on {}", offsets.length, topic);
            Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
            for (int i = 0; i < offsets.length; i++) {
                TopicPartition partition = new TopicPartition(topic, i);
                offsetsToCommit.put(partition, new OffsetAndMetadata(offsets[i] + 1)); // +1 to acknowledge the message
            }
            kafkaConsumer.commitSync(offsetsToCommit);
            return null;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}