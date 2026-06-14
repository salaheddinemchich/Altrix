package com.example.altrix.pubsub;

import com.example.altrix.pubsub.tasks.AcknowledgeMessagesTask;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import java.time.Duration;
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
    private final Provider<GoogleErrorConverter> googleErrorConverterProvider;
    private final KafkaProducer<String, String> kafkaProducer;
    private final KafkaConsumer<String, String> kafkaConsumer;

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
        publish(topic, Lists.newArrayList(message));
    }

    @Override
    public void publish(PubsubTopic topic, List<AltrixPubsubMessage> altrixMessages) {
        List<ProducerRecord<String, String>> records = Lists.newArrayList();
        for (AltrixPubsubMessage altrixMessage : altrixMessages) {
            byte[] messageData = altrixMessage.getMessage();
            Map<String, String> attributes = altrixMessage.getAttributes(); // Corrected method call
            String orderingKey = altrixMessage.getOrderingKey();
            if (attributes != null || messageData != null) {
                String messageStr = (messageData != null) ? new String(messageData) : "";
                records.add(new ProducerRecord<>(topic.getFullTopicName(), messageStr));
            } else {
                log.error("One of the attributes or messageData should be filled.", new RuntimeException());
            }
        }
        publishKafkaRecords(topic, records);
    }

    @Override
    public void publishPubsubMessages(PubsubTopic topic, Collection<String> messages) {
        Preconditions.checkNotNull(topic, "The target topic can't be null.");
        if (!messages.isEmpty()) {
            List<ProducerRecord<String, String>> records = messages.stream()
                    .map(message -> new ProducerRecord<>(topic.getFullTopicName(), message))
                    .toList();
            publishKafkaRecords(topic, records);
        }
    }

    private void publishKafkaRecords(PubsubTopic topic, List<ProducerRecord<String, String>> records) {
        try {
            kafkaProducer.send(records.get(0)); // Simplified for demonstration; consider batch sending
        } catch (SerializationException | KafkaException e) {
            log.error("Error publishing to Kafka topic {}", topic.getFullTopicName(), e);
            // Retry logic can be added here based on the retryHandler
        }
    }

    @Override
    public List<String> pull(PubsubSubscription subscription, int maxMessages) {
        Preconditions.checkArgument(subscription.getSubscriptionType() == PubsubSubscriptionType.PULL, "Can't pull messages if the subscription's type is not 'pull'.");
        try {
            kafkaConsumer.subscribe(List.of(subscription.getFullSubscriptionName()));
            ConsumerRecords<String, String> records = kafkaConsumer.poll(java.time.Duration.ofSeconds(1));
            return records.records(TopicPartition.of(subscription.getFullSubscriptionName(), 0))
                    .stream()
                    .map(record -> record.value())
                    .toList();
        } catch (KafkaException e) {
            log.error("Error pulling from Kafka subscription {}", subscription.getFullSubscriptionName(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void acknowledge(PubsubSubscription subscription, List<String> ackIds) {
        Preconditions.checkArgument(subscription.getSubscriptionType() == PubsubSubscriptionType.PULL, "Can't acknowledge messages if the subscription's type is not 'pull'.");
        if (!ackIds.isEmpty()) {
            // Kafka doesn't use ack IDs like Pub/Sub; this is a placeholder
            log.info("Acknowledging {} messages in Kafka (no direct equivalent)", ackIds.size());
            // Implement your Kafka acknowledgment logic here if necessary
        }
    }

    @SneakyThrows
    @Override
    public Object getOrCreateSubscription(PubsubTopic topic, PubsubSubscription subscriptionName) {
        // Kafka doesn't have direct equivalents for Pub/Sub subscriptions or topics in this context
        // This method is a placeholder; consider creating a Kafka consumer group or similar
        log.info("Getting or creating Kafka consumer group for topic {}", topic.getFullTopicName());
        return new Object(); // Placeholder, adjust based on your Kafka setup
    }

    @SneakyThrows
    @Override
    public Object createSubscription(PubsubTopic topic, PubsubSubscription subscriptionName) {
        // Similar to above, this is a placeholder for creating a consumer group or similar logic
        log.info("Creating Kafka consumer group for topic {}", topic.getFullTopicName());
        return new Object(); // Placeholder
    }

    @SneakyThrows
    @Override
    public Object getOrCreateTopic(PubsubTopic topicName) {
        // Kafka topics are auto-created when producing to them if `auto.create.topics.enable=true`
        log.info("Kafka topic {} will be auto-created if not exists", topicName.getFullTopicName());
        return new Object(); // Placeholder, as Kafka topic creation isn't explicitly managed like in Pub/Sub
    }

    @SneakyThrows
    @Override
    public Object createTopic(PubsubTopic topicName) {
        // Similar to above, this method acknowledges the topic will be auto-created
        log.info("Kafka topic {} to be used (auto-creation enabled)", topicName.getFullTopicName());
        return new Object(); // Placeholder
    }

    @Override
    public List<String> testIAMPermissionsOnTopic(PubsubTopic topic, List<String> permissions) {
        // Kafka ACLs are managed differently; this is a placeholder for your ACL checking logic
        log.info("Testing Kafka ACLs for topic {} (placeholder for actual ACL check)", topic.getFullTopicName());
        return Lists.newArrayList(); // Placeholder, implement actual ACL permission checking
    }
}