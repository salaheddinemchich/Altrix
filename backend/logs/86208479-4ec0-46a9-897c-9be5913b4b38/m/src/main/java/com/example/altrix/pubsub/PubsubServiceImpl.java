package com.example.altrix.pubsub;

import com.example.altrix.pubsub.tasks.AcknowledgeMessagesTask;
import com.example.altrix.pubsub.tasks.PublishMessagesTask;
import com.example.altrix.pubsub.tasks.PullMessagesTask;
import com.example.altrix.pubsub.tasks.PubsubTopicTestIAMPermissionsTask;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Time;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.admin.AdminClient;
/** Hand-instantiated by {@link PubsubServiceProducer} — kept out of CDI's * auto-discovery with {@link Vetoed} so the producer's bean isn't shadowed * by an ambient {@code @Dependent} bean of the same interface (was causing * WELD-001409 "Ambiguous dependencies for type PubsubService"). */
@Slf4j
@Vetoed
@AllArgsConstructor(onConstructor_ = @Inject)
public class PubsubServiceImpl implements PubsubService {
    private final RetryHandler retryHandler;
    private final Provider<IGoogleErrorConverter> googleErrorConverterProvider;
    private final KafkaProducer<String, String> kafkaProducer;
    private final KafkaConsumer<String, String> kafkaConsumer;

    public void publish(String topic, Map<String, String> attributes) {
        publish(topic, new AltrixPubsubMessage(attributes, null));
    }

    public void publish(String topic, byte[] message) {
        publish(topic, new AltrixPubsubMessage(Collections.emptyMap(), message));
    }

    public void publish(String topic, AltrixPubsubMessage message) {
        publish(topic, Collections.singletonList(message));
    }

    public void publish(String topic, List<AltrixPubsubMessage> altrixMessages) {
        List<ProducerRecord<String, String>> kafkaRecords = Lists.newArrayList();
        for (AltrixPubsubMessage altrixMessage : altrixMessages) {
            byte[] messageData = altrixMessage.getMessage();
            Map<String, String> attributes = altrixMessage.getAttributes();
            String orderingKey = altrixMessage.getOrderingKey();
            if (attributes != null || messageData != null) {
                kafkaRecords.add(mapAltrixPubsubMessageToKafka(attributes, messageData, orderingKey, topic));
            } else {
                log.error("One of the attributes or messageData should be filled.");
            }
        }
        publishKafkaRecords(topic, kafkaRecords);
    }

    private ProducerRecord<String, String> mapAltrixPubsubMessageToKafka(Map<String, String> attributes, byte[] messageData, String orderingKey, String topic) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, new String(messageData));
        // Attributes and ordering key handling may vary based on exact Kafka setup requirements
        // For simplicity, attributes are ignored here; implement as necessary
        return record;
    }

    public void publishKafkaRecords(String topic, Collection<ProducerRecord<String, String>> kafkaRecords) {
        Preconditions.checkNotNull(topic, "The target topic can't be null.");
        if (!kafkaRecords.isEmpty()) {
            kafkaRecords.forEach(record -> kafkaProducer.send(record));
        }
    }

    public ConsumerRecords<String, String> pull(String subscription, int maxMessages) {
        Preconditions.checkArgument(subscription.contains("pull"), "Can't pull messages if the subscription's type is not 'pull'.");
        kafkaConsumer.subscribe(List.of(subscription));
        return kafkaConsumer.poll(Time.milliseconds(100));
    }

    public void acknowledge(String subscription, List<String> ackIds) {
        Preconditions.checkArgument(subscription.contains("pull"), "Can't acknowledge messages if the subscription's type is not 'pull'.");
        if (!ackIds.isEmpty()) {
            kafkaConsumer.commitSync();
        }
    }

    @SneakyThrows
    public void getOrCreateKafkaTopic(String topicName) {
        // Kafka auto-creation is typically enabled; this method is a placeholder
        // Implement using AdminClient if manual topic creation is required
        log.info("Kafka topic {} assumed to exist or auto-created.", topicName);
    }

    public List<String> testIAMPermissionsOnTopic(String topic, List<String> permissions) {
        // TODO altrix: Kafka does not have direct IAM permission testing like Pub/Sub
        // Implement custom permission checks based on your Kafka security setup
        log.warn("testIAMPermissionsOnTopic not directly applicable to Kafka; custom implementation needed.");
        return Collections.emptyList();
    }

    // Modified to match interface contract
    public List<String> testTopicPermissions(String topic, List<String> permissions) {
        // TODO altrix: Implement or remove based on actual Kafka permission requirements
        log.warn("testTopicPermissions not implemented");
        return Collections.emptyList();
    }

    // Modified to match interface contract
    public void testAclPermissions(String topic, List<String> permissions) {
        // TODO altrix: Implement or remove based on actual Kafka ACL requirements
        log.warn("testAclPermissions not implemented");
    }

    // Modified to match interface contract
    public void publishKafkaMessages(String topic, Collection<String> messages) {
        // Already implemented through publish methods
        log.info("publishKafkaMessages redirecting to existing publish logic.");
        messages.forEach(message -> publish(topic, message.getBytes()));
    }

    public void subscribe(String topic, String subscription) {
        // TODO altrix: Implement or remove based on actual Kafka consumer group requirements
        log.warn("subscribe not implemented");
    }

    public Map<String, Object> getKafkaProducerConfig() {
        // Example config; adjust according to your Kafka setup
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        // Add other necessary producer configs
        return config;
    }

    public Map<String, Object> getKafkaConsumerConfig(String groupId) {
        // Example config; adjust according to your Kafka setup
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        config.put("group.id", groupId);
        // Add other necessary consumer configs
        return config;
    }

    public Object getOrCreateTopic(String topic, short param1, short param2) {
        // TODO altrix: Implement or remove based on actual requirements
        log.warn("getOrCreateTopic with shorts not implemented");
        return null;
    }

    public Object getOrCreateConsumerGroup(String group) {
        // TODO altrix: Implement or remove based on actual requirements
        log.warn("getOrCreateConsumerGroup not implemented");
        return null;
    }

    public void testTopicPermissions(String topic, String permission) {
        // TODO altrix: Implement or remove based on actual requirements
        log.warn("testTopicPermissions not implemented");
    }

    public void createConsumerGroup(String group) {
        // TODO altrix: Implement or remove based on actual requirements
        log.warn("createConsumerGroup not implemented");
    }

    public ConsumerRecords<String, String> pull(String subscription, String ackId, int maxMessages) {
        // TODO altrix: Implement or remove based on actual requirements
        log.warn("pull with three params not implemented");
        return ConsumerRecords.empty();
    }
}