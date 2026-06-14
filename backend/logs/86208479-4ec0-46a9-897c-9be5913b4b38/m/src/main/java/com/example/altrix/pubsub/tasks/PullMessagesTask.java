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
    private final int pollTimeoutSeconds;

    public ConsumerRecords<String, String> call() throws Exception {
        try {
            // Subscribe to the topic if not already subscribed (assuming topicName is the Kafka topic)
            if (!kafkaConsumer.subscription().contains(topicName)) {
                kafkaConsumer.subscribe(List.of(topicName));
            }
            // Seek to the beginning for new consumers or if seeking is explicitly required
            // kafkaConsumer.seekToBeginning(Collections.singleton(topicName));
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(pollTimeoutSeconds));
            // Optional: Commit the records after processing (for simplicity, auto-commit is disabled here)
            // kafkaConsumer.commitSync();
            return records;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }

    // Example of how the KafkaConsumer could be injected or created (typically in a config class)
    // This part would usually be in a separate config or utility class, not in the task itself
    // @Bean
    // @Singleton
    // public KafkaConsumer<String, String> kafkaConsumer() {
    //     Properties props = new Properties();
    //     props.put("bootstrap.servers", "localhost:9092");
    //     props.put("group.id", "altrix-group");
    //     props.put("key.deserializer", StringDeserializer.class);
    //     props.put("value.deserializer", StringDeserializer.class);
    //     props.put("auto.offset.reset", "earliest"); // or "latest"
    //     props.put("enable.auto.commit", "false"); // Manual commit in the task if needed
    //     return new KafkaConsumer<>(props);
    // }
}