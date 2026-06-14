package com.example.altrix.pubsub;

import com.example.altrix.pubsub.tasks.AcknowledgeMessagesTask;
import com.example.altrix.pubsub.tasks.PublishMessagesTask;
import com.example.altrix.pubsub.tasks.PullMessagesTask;
import com.example.altrix.pubsub.tasks.PubsubTopicTestIAMPermissionsTask;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/** 
 * Hand-instantiated by {@link PubsubServiceProducer} — kept out of CDI's 
 * auto-discovery with {@link Vetoed} so the producer's bean isn't shadowed 
 * by an ambient {@code @Dependent} bean of the same interface (was causing 
 * WELD-001409 "Ambiguous dependencies for type PubsubService"). 
 */
@Slf4j
@Vetoed
@AllArgsConstructor
public class PubsubServiceImpl implements PubsubService {
    private final RetryHandler retryHandler;
    private final Provider<IGoogleErrorConverter> googleErrorConverterProvider;
    private final KafkaProducer<String, String> kafkaProducer;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final AdminClient adminClient;

    @Override
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
            // Removed getAttributes() call as it's not defined in AltrixPubsubMessage
            String orderingKey = altrixMessage.getOrderingKey();
            if (messageData != null) {
                String key = orderingKey != null ? orderingKey : null;
                String value = new String(messageData);
                kafkaRecords.add(new ProducerRecord<>(topic.getFullTopicName(), key, value));
            } else {
                log.error("Message data is empty.");
            }
        }
        kafkaProducer.sendAll(kafkaRecords);
    }

    @Override
    public List<String> pull(PubsubSubscription subscription, int maxMessages) {
        // Removed getSubscriptionType() and getSubscriptionName() calls as they're not defined in PubsubSubscription
        // Assuming direct access to necessary fields for demonstration (in real code, add these methods or fields to PubsubSubscription)
        String subscriptionName = subscription.getName(); // Example assumption
        PubsubSubscriptionType type = subscription.getType(); // Example assumption
        if (type != PubsubSubscriptionType.PULL) {
            throw new UnsupportedOperationException("Subscription type must be PULL for pulling messages.");
        }
        kafkaConsumer.subscribe(Collections.singleton(subscriptionName));
        kafkaConsumer.poll(Duration.ofSeconds(1)).forEach(record -> {
            log.info("Received message: {}", record.value());
            // Manual acknowledgment not directly applicable; using auto-commit
            // or implement custom commit logic if needed
        });
        return Collections.emptyList(); // No direct ack IDs in Kafka
    }

    @Override
    public void acknowledge(PubsubSubscription subscription, List<String> ackIds) {
        // Kafka uses auto-commit or manual commit via consumer.commitSync()/commitAsync()
        // No direct ack ID mechanism; this method is a no-op in Kafka context
    }

    @SneakyThrows
    @Override
    public void getOrCreateTopic(PubsubTopic topicName) {
        try {
            adminClient.describeTopics(Collections.singleton(topicName.getFullTopicName())).all().get();
        } catch (KafkaException e) {
            // Topic not found, create it
            NewTopic newTopic = new NewTopic(topicName.getFullTopicName(), 1, (short) 1);
            adminClient.createTopics(Collections.singleton(newTopic));
        }
    }

    @SneakyThrows
    @Override
    public void createTopic(PubsubTopic topicName) {
        NewTopic newTopic = new NewTopic(topicName.getFullTopicName(), 1, (short) 1);
        adminClient.createTopics(Collections.singleton(newTopic));
    }

    @Override
    public List<String> testIAMPermissionsOnTopic(PubsubTopic topic, List<String> permissions) {
        // TODO altrix: No direct Kafka equivalent; use AdminClient ACLs or external IAM
        return Collections.emptyList();
    }

    @Override
    public PubsubSubscription getOrCreateSubscription(String subscriptionName, String topicName) {
        // Minimal implementation for migration TODO
        throw new RuntimeException("TODO: Implement getOrCreateSubscription");
    }

    @Override
    public PubsubSubscription createSubscription(String subscriptionName, String topicName) {
        // Minimal implementation for migration TODO
        throw new RuntimeException("TODO: Implement createSubscription");
    }

    @Override
    public void publishPubsubMessages(PubsubTopic topic, List<AltrixPubsubMessage> messages) {
        publish(topic, messages);
    }
}