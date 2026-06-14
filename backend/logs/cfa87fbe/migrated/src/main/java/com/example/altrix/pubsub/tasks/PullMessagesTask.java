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
    private final int pollTimeoutMs;

    public ConsumerRecords<String, String> call() throws Exception {
        try {
            // Seek to the beginning for demonstration; adjust based on your needs
            kafkaConsumer.seekToBeginning(Collections.singleton(topicName));
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(pollTimeoutMs));
            if (records.isEmpty()) {
                return Collections.emptyConsumerRecords();
            }
            // Example: Commit after processing (assuming auto-commit is disabled)
            kafkaConsumer.commitSync();
            return records;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }

    // Example configuration for the KafkaConsumer (typically set via @Produces or external config)
    public static Properties kafkaConsumerProps(String bootstrapServers, String groupId) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        // Additional config (e.g., auto.offset.reset, enable.auto.commit)
        props.put("auto.offset.reset", "earliest"); // or "latest", "none"
        props.put("enable.auto.commit", "false"); // Manual commit in this example
        return props;
    }
}