package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
@RequiredArgsConstructor
@Slf4j
public class PullMessagesTask extends RetryTask<ConsumerRecords<String, String>> {
    private static final Logger log = LoggerFactory.getLogger(PullMessagesTask.class);
    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String topic;
    private final int maxMessages;

    @Override
    public ConsumerRecords<String, String> call() throws Exception {
        try {
            log.debug("Pulling up to {} messages from topic {}", maxMessages, topic);
            kafkaConsumer.subscribe(Collections.singleton(topic));
            ConsumerRecords<String, String> records = kafkaConsumer.poll(java.time.Duration.ofMillis(100));
            log.debug("Received {} records from topic {}", records.count(), topic);
            return records;
        } catch (KafkaException e) {
            throw errorConverter.convert(e);
        }
    }
}