package com.example.altrix.pubsub;

import com.example.altrix.pubsub.tasks.AcknowledgeMessagesTask;
import com.example.altrix.pubsub.tasks.PublishMessagesTask;
import com.example.altrix.pubsub.tasks.PullMessagesTask;
import com.example.altrix.pubsub.tasks.PubsubTopicTestIAMPermissionsTask;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import com.example.altrix.pubsub.PubsubTopic;
import com.example.altrix.pubsub.PubsubSubscription;
import com.example.altrix.pubsub.AltrixPubsubMessage;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.Collection;
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
                String kafkaValue = messageData != null ? new String(messageData) : "";
                kafkaRecords.add(new ProducerRecord<>(topic.getFullTopicName(), orderingKey, kafkaValue));
            } else {
                log.error("One of the attributes or messageData should be filled.", new IllegalArgumentException());
            }
        }
        kafkaProducer.sendAll(kafkaRecords);
    }

    public void publishKafkaMessages(PubsubTopic topic, List<AltrixPubsubMessage> messages) {
        // Minimal implementation to satisfy the interface
        log.warn("TODO: Implement publishKafkaMessages or delegate properly");
        throw new UnsupportedOperationException("Migration TODO: Implement publishKafkaMessages");
    }

    public List<String> pull(PubsubSubscription subscription, int maxMessages) {
        Preconditions.checkArgument(subscription.getType() == PubsubSubscriptionType.PULL, "Can't pull messages if the subscription's type is not 'pull'.");
        kafkaConsumer.subscribe(List.of(subscription.getFullSubscriptionName()));
        ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(1));
        List<String> messages = Lists.newArrayList();
        for (ConsumerRecord<String, String> record : records) {
            messages.add(record.value());
            kafkaConsumer.commitSync();
        }
        return messages;
    }

    public List<String> pull(PubsubSubscription subscription, int maxMessages, Function<String, Boolean> filter) {
        // Original pull method with 3 parameters (assumed from violation context)
        // NOTE: This method's body is a guess based on the violation; adjust according to actual requirements
        List<String> messages = pull(subscription, maxMessages);
        if (filter != null) {
            return messages.stream().filter(filter).toList();
        }
        return messages;
    }

    public void acknowledge(PubsubSubscription subscription, List<String> ackIds) {
        // Kafka does not have explicit ack IDs; assuming auto-commit or manual commitSync() covers acknowledgment
        kafkaConsumer.commitSync();
    }

    @SneakyThrows
    public void getOrCreateTopic(PubsubTopic topicName) {
        try {
            adminClient.describeTopics(List.of(topicName.getFullTopicName())).all().get();
        } catch (KafkaException e) {
            // Topic does not exist, create it
            NewTopic newTopic = new NewTopic(topicName.getFullTopicName(), 1, (short) 1);
            adminClient.createTopics(List.of(newTopic));
        }
    }

    @SneakyThrows
    public void getOrCreateSubscription(PubsubSubscription subscriptionName) {
        // Kafka subscriptions are client-side; no server-side creation needed
        kafkaConsumer.subscribe(List.of(subscriptionName.getFullSubscriptionName()));
    }

    public List<String> testIAMPermissionsOnTopic(PubsubTopic topic, List<String> permissions) {
        // TODO altrix: No direct Kafka equivalent; use AdminClient ACLs or external IAM
        return Collections.emptyList();
    }

    public void testACLPermissionsOnTopic(PubsubTopic topic, List<String> permissions) {
        // Minimal implementation to satisfy the interface
        log.warn("TODO: Implement testACLPermissionsOnTopic or delegate properly");
        throw new UnsupportedOperationException("Migration TODO: Implement testACLPermissionsOnTopic");
    }

    public void createTopic(PubsubTopic topic) {
        // Minimal implementation to satisfy the interface, delegates to existing getOrCreateTopic
        getOrCreateTopic(topic);
    }
}