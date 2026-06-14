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
    private final org.apache.kafka.clients.consumer.KafkaConsumer<String, String> kafkaConsumer;
    private final org.apache.kafka.clients.admin.AdminClient adminClient;

    @Override // Overloading 4 Methods publish(...)
    public void publish(PubsubTopic topic, Map<String, String> attributes) {
        publish(topic, new AltrixPubsubMessage(attributes, null));
    }

    @Override
    public void publish(PubsubTopic topic, byte[] message) {
        publish(topic, new AltrixPubsubMessage(Collections.emptyMap(), message));
    }

    @Override
    public void publish(PubsubTopic topic, AltrixPubsubMessage message) {
        publish(topic, Collections.singletonList(message));
    }

    @Override
    public void publish(PubsubTopic topic, List<AltrixPubsubMessage> altrixMessages) {
        List<ProducerRecord<String, String>> kafkaRecords = Lists.newArrayList();
        for (AltrixPubsubMessage altrixMessage : altrixMessages) {
            byte[] messageData = altrixMessage.getMessage();
            Map<String, String> attributes = altrixMessage.getAttributes();
            String orderingKey = altrixMessage.getOrderingKey();
            if (attributes != null || messageData != null) {
                kafkaRecords.add(mapAltrixPubsubMessageToKafkaRecord(attributes, messageData, orderingKey, topic.getFullTopicName()));
            } else {
                log.error("One of the attributes or messageData should be filled.", new RuntimeException());
            }
        }
        publishKafkaRecords(kafkaRecords);
    }

    private ProducerRecord<String, String> mapAltrixPubsubMessageToKafkaRecord(Map<String, String> attributes, byte[] messageData, String orderingKey, String topicName) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topicName, orderingKey, new String(messageData));
        // Attributes are not directly supported in Kafka's ProducerRecord in the same way as Pub/Sub,
        // so we either ignore them or implement a custom serialization that embeds attributes into the value.
        // For simplicity, attributes are ignored in this example. You may need to adjust based on your requirements.
        return record;
    }

    private void publishKafkaRecords(List<ProducerRecord<String, String>> records) {
        if (!records.isEmpty()) {
            records.forEach(record -> kafkaProducer.send(record));
        }
    }

    @Override
    public List<ConsumerRecord<String, String>> pull(PubsubSubscription subscription, int maxMessages) {
        Preconditions.checkArgument(subscription.getType() == PubsubSubscriptionType.PULL, "Can't pull messages if the subscription's type is not 'pull'.");
        kafkaConsumer.subscribe(List.of(subscription.getFullSubscriptionName()));
        ConsumerRecords<String, String> records = kafkaConsumer.poll(Time.millis(100));
        kafkaConsumer.commitSync(); // Auto-commit after pull for simplicity
        return records.records(subscription.getFullSubscriptionName());
    }

    @Override
    public void acknowledge(PubsubSubscription subscription, List<String> ackIds) {
        Preconditions.checkArgument(subscription.getType() == PubsubSubscriptionType.PULL, "Can't acknowledge messages if the subscription's type is not 'pull'.");
        if (!ackIds.isEmpty()) {
            // Kafka doesn't use ack IDs like Pub/Sub. Instead, it commits offsets.
            // The following is a simplified example and might need adjustments based on your offset management strategy.
            kafkaConsumer.commitSync();
        }
    }

    @SneakyThrows
    @Override
    public void getOrCreateTopic(PubsubTopic topicName) {
        // Kafka auto-creates topics by default in many configurations. 
        // If not, you'd use AdminClient to create topics explicitly.
        // For simplicity, assuming auto-creation is enabled or the topic already exists.
        // If you need to ensure creation, uncomment and configure the following:
        // adminClient.createTopics(Collections.singleton(new NewTopic(topicName.getFullTopicName(), 1, (short) 1)));
    }

    @Override
    public List<String> testIAMPermissionsOnTopic(PubsubTopic topic, List<String> permissions) {
        // TODO altrix: Kafka doesn't have direct IAM permission testing like Pub/Sub.
        // You would typically handle permissions at the Kafka cluster level or through external IAM systems.
        // This method's implementation depends on your specific security setup.
        return Collections.emptyList(); // Placeholder, implement based on your Kafka security model
    }
}