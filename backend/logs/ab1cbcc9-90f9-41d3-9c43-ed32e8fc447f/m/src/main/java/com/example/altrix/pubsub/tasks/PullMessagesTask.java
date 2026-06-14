package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import java.util.ArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
public class PullMessagesTask extends RetryTask<List<String>> {
    private static final Logger log = LoggerFactory.getLogger(PullMessagesTask.class);
    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final TopicPartition partition;
    private final int maxMessages;

    public PullMessagesTask(IGoogleErrorConverter errorConverter, KafkaConsumer<String, String> kafkaConsumer, TopicPartition partition, int maxMessages) {
        this.errorConverter = errorConverter;
        this.kafkaConsumer = kafkaConsumer;
        this.partition = partition;
        this.maxMessages = maxMessages;
    }

    @Override
    public List<String> call() throws Exception {
        try {
            log.debug("Pulling up to {} messages from partition {}", maxMessages, partition);
            kafkaConsumer.wakeup(); // Ensure we don't block indefinitely
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(100));
            List<String> messages = new ArrayList<>();
            for (ConsumerRecord<String, String> record : records) {
                messages.add(record.value());
            }
            return messages.isEmpty() ? Collections.emptyList() : messages;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        } finally {
            kafkaConsumer.commitSync(); // Synchronous commit for simplicity
        }
    }
}