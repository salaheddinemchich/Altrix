package com.example.altrix.pubsub;

import com.example.altrix.pubsub.tasks.AcknowledgeMessagesTask;
import com.example.altrix.pubsub.tasks.PublishMessagesTask;
import com.example.altrix.pubsub.tasks.PullMessagesTask;
import com.example.altrix.pubsub.tasks.PubsubTopicTestIAMPermissionsTask;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpStatusCodes;
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
import java.time.Duration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
/** 
 * Hand-instantiated by {@link PubsubServiceProducer} — kept out of CDI's 
 * auto-discovery with {@link Vetoed} so the producer's bean isn't shadowed 
 * by an ambient {@code @Dependent} bean of the same interface (was causing 
 * WELD-001409 "Ambiguous dependencies for type PubsubService"). 
 */
@Slf4j
@Vetoed
@AllArgsConstructor(onConstructor_ = @Inject)
public class PubsubServiceImpl implements PubsubService {
    private final RetryHandler retryHandler;
    private final Provider<IGoogleErrorConverter> googleErrorConverterProvider;
    private final KafkaProducer<String, String> kafkaProducer;
    private final KafkaConsumer<String, String> kafkaConsumer;

    public void publish(PubsubTopic topic, Map<String, String> attributes) {
        publish(topic, new AltrixPubsubMessage(attributes, null));
    }

    public void publish(PubsubTopic topic, byte[] message) {
        publish(topic, new AltrixPubsubMessage(Collections.emptyMap(), message));
    }

    public void publish(PubsubTopic topic, AltrixPubsubMessage message) {
        publish(topic, Collections.singletonList(message));
    }

    public void publish(PubsubTopic topic, List<AltrixPubsubMessage> altrixMessages) {
        List<ProducerRecord<String, String>> kafkaRecords = Lists.newArrayList();
        for (AltrixPubsubMessage altrixMessage : altrixMessages) {
            byte[] messageData = altrixMessage.getMessage();
            Map<String, String> attributes = altrixMessage.getAttributes();
            String orderingKey = altrixMessage.getOrderingKey();
            if (attributes != null || messageData != null) {
                kafkaRecords.add(mapAltrixToKafkaRecord(topic.getName(), attributes, messageData, orderingKey));
            } else {
                log.error("One of the attributes or messageData should be filled.", new RuntimeException());
            }
        }
        kafkaProducer.sendAll(kafkaRecords);
    }

    private ProducerRecord<String, String> mapAltrixToKafkaRecord(String topic, Map<String, String> attributes, byte[] messageData, String orderingKey) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, new String(messageData));
        // Attributes and ordering key handling may vary based on Kafka configuration
        // For simplicity, attributes are added as headers (if supported by your Kafka version)
        if (attributes != null) {
            record.headers().add("attributes", new String(messageData)); // Simplified; implement proper serialization
        }
        if (orderingKey != null) {
            // Kafka doesn't have direct ordering key support like Pub/Sub; 
            // this might be handled at the application level or via custom partitioning
            log.warn("Ordering Key '{}' not directly supported in Kafka, considering custom partitioning or application logic.", orderingKey);
            // Example custom partitioning based on ordering key (simplified)
            record.partition(orderingKey.hashCode() % kafkaProducer.partitionsFor(topic).size());
        }
        return record;
    }

    public List<ConsumerRecord<String, String>> pull(PubsubSubscription subscription, int maxMessages) {
        Preconditions.checkArgument(subscription.getType() == PubsubSubscriptionType.PULL, "Can't pull messages if the subscription's type is not 'pull'.");
        kafkaConsumer.subscribe(List.of(subscription.getName()));
        ConsumerRecords<String, String> records = kafkaConsumer.poll(java.time.Duration.ofMillis(100));
        kafkaConsumer.commitSync(); // Auto-commit for simplicity; adjust based on your needs
        return records.records(subscription.getName());
    }

    public void acknowledge(PubsubSubscription subscription, List<String> ackIds) {
        Preconditions.checkArgument(subscription.getType() == PubsubSubscriptionType.PULL, "Can't acknowledge messages if the subscription's type is not 'pull'.");
        if (!ackIds.isEmpty()) {
            // Kafka acknowledges by offset, not message IDs. This is a simplification.
            // In a real scenario, you'd manage offsets based on consumed records.
            kafkaConsumer.commitSync();
            log.warn("Acknowledge in Kafka is offset-based, not message ID. This implementation simplifies to auto-commit.");
        }
    }

    @SneakyThrows
    public void createTopic(PubsubTopic topicName, String partition, Integer replicas) {
        // Kafka topic creation example with basic config
        NewTopic newTopic = new NewTopic(topicName.getName(), Integer.parseInt(partition), (short) replicas);
        AdminClient adminClient = AdminClient.create(kafkaProducer.configs());
        adminClient.createTopics(Collections.singleton(newTopic));
    }

    // TODO altrix: Implement getKafkaConsumer with proper consumer group management
    public KafkaConsumer<String, String> getKafkaConsumer(String topic) {
        // Basic example, consider consumer group management and configuration
        return new KafkaConsumer<>(kafkaConsumer.configs());
    }

    // Additional TODOs and simplifications for other methods based on the original
    public void publishKafkaMessages(PubsubTopic topic, List<AltrixPubsubMessage> messages) {
        publish(topic, messages); // Utilize the existing publish method
    }

    public Object getKafkaProducer() {
        return kafkaProducer;
    }

    public void publish(PubsubTopic topic, byte[] message, Map<String, String> attributes) {
        publish(topic, new AltrixPubsubMessage(attributes, message));
    }

    public List<ConsumerRecord<String, String>> pull(PubsubSubscription subscription, int maxMessages, String filter) {
        List<ConsumerRecord<String, String>> records = pull(subscription, maxMessages);
        // Filter implementation based on 'filter' parameter (simplified)
        return records.stream().filter(record -> record.value().contains(filter)).toList();
    }

    public List<String> testSecurityPermissionsOnTopic(PubsubTopic topic, List<String> permissions) {
        // Kafka permission testing is more about ACLs and not directly comparable
        log.warn("TODO altrix: Kafka ACLs are configured differently, implement proper permission testing.");
        return Collections.emptyList(); // Placeholder
    }
}