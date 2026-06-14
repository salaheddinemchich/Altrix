package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.util.Collections;
import java.util.List;

import java.time.Duration;
@Slf4j
@RequiredArgsConstructor
public class PullMessagesTask extends RetryTask<List<ConsumerRecord<String, String>>> {
    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String topicName; // Renamed from fullSubscriptionName to reflect Kafka terminology
    private final int maxMessages;

    public List<ConsumerRecord<String, String>> call() throws Exception {
        try {
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(1)); // Adjust duration as needed
            List<ConsumerRecord<String, String>> messages = records.isEmpty() ? Collections.emptyList() : records.toList();
            // TODO altrix: Manual ack handling needed for Kafka (e.g., commit after processing)
            kafkaConsumer.commitSync(); // Example auto-commit for simplicity; adjust based on your ack strategy
            return messages;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}