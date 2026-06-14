package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

@Slf4j
@RequiredArgsConstructor
public class PullMessagesTask extends RetryTask<ConsumerRecords<String, String>> {
    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final String topicName;
    private final int maxMessages;

    public ConsumerRecords<String, String> call() throws Exception {
        try {
            kafkaConsumer.seekToBeginning(Collections.singleton(topicName));
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(maxMessages));
            return records;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}