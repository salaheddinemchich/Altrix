package com.example.altrix.pubsub.tasks;

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
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String topicName;
    private final int maxMessages;

    public List<ConsumerRecord<String, String>> call() throws Exception {
        try {
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(1));
            List<ConsumerRecord<String, String>> messages = records.records(topicName);
            return messages.isEmpty() ? Collections.emptyList() : messages;
        } catch (Exception e) {
            throw new RuntimeException(e); // Simplified error handling for now
        }
    }
}