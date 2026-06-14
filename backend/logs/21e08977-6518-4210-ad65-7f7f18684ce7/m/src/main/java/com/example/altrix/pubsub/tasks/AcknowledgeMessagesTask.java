package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.HashMap;

@Slf4j
@RequiredArgsConstructor
public class AcknowledgeMessagesTask extends RetryTask<Void> {
    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String topic;
    private final long[] offsets;

    public Void call() throws Exception {
        try {
            log.debug("Acknowledging {} message(s) on topic {}", offsets.length, topic);
            Map<TopicPartition, Long> offsetsToCommit = new HashMap<>();
            for (int i = 0; i < offsets.length; i++) {
                TopicPartition tp = new TopicPartition(topic, i);
                offsetsToCommit.put(tp, offsets[i]);
            }
            kafkaConsumer.commitSync(offsetsToCommit);
            return null;
        } catch (RuntimeException e) {
            throw errorConverter.convert(e);
        }
    }
}