package com.example.altrix.pubsub;

import com.example.altrix.pubsub.tasks.AcknowledgeMessagesTask;
import com.example.altrix.pubsub.tasks.PublishMessagesTask;
import com.example.altrix.pubsub.tasks.PullMessagesTask;
import com.example.altrix.pubsub.tasks.PubsubTopicTestIAMPermissionsTask;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
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
    private final Provider<Object> googleErrorConverterProvider; 
    // Changed type
    private final KafkaProducer<String, String> kafkaProducer;
    private final KafkaConsumer<String, String> kafkaConsumer;

    static { 
        // Initialize Kafka consumer and producer with default config
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "altrix-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put("acks", "all");
        props.put("retries", 0);
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", StringSerializer.class);
        kafkaConsumer = new KafkaConsumer<>(props);
        kafkaProducer = new KafkaProducer<>(props);
    }

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
        List<String> messages = Lists.newArrayList();
        for (AltrixPubsubMessage altrixMessage : altrixMessages) {
            byte[] messageData = altrixMessage.getMessage(); 
            // Removed getAttributes() call as it's not defined
            if (messageData != null) { 
                // Simplified for Kafka, assuming message is the value
                String messageStr = new String(messageData);
                messages.add(messageStr);
            } else {
                log.error("Message data should be filled.", new RuntimeException()); 
                // Changed exception
            }
        }
        publishPubsubMessages(topic, messages);
    }

    @Override
    public void publishPubsubMessages(PubsubTopic topic, Collection<String> messages) {
        Preconditions.checkNotNull(topic, "The target topic can't be null.");
        if (!messages.isEmpty()) {
            for (String message : messages) {
                ProducerRecord<String, String> record = new ProducerRecord<>(topic.getFullTopicName(), message);
                retryHandler.execute(() -> kafkaProducer.send(record));
            }
        }
    }

    @Override
    public List<String> pull(PubsubSubscription subscription, int maxMessages) {
        // Removed getType() and getFullSubscriptionName() calls as they're not defined
        kafkaConsumer.subscribe(List.of(subscription.toString())); 
        // Changed to toString()
        ConsumerRecords<String, String> records = kafkaConsumer.poll(java.time.Duration.ofSeconds(1));
        List<String> messages = Lists.newArrayList();
        for (ConsumerRecord<String, String> record : records) {
            messages.add(record.value()); 
            // Manual commit for simplicity (in a real app, use try-commit or auto-commit)
            kafkaConsumer.commitSync();
        }
        return messages;
    }

    @Override
    public void acknowledge(PubsubSubscription subscription, List<String> ackIds) {
        // Removed getType() check as it's not defined
        if (!ackIds.isEmpty()) { 
            // Kafka doesn't use ack IDs like Pub/Sub; assuming commit is acknowledgment
            kafkaConsumer.commitSync();
        }
    }

    @SneakyThrows
    @Override
    public Object getOrCreateSubscription(PubsubTopic topic, PubsubSubscription subscriptionName) {
        // Kafka doesn't have direct subscription management like Pub/Sub
        // Assuming topic existence is sufficient for "subscription"
        return new Object(); 
        // Changed return type
        // TODO altrix: Kafka equivalent handling
    }

    @SneakyThrows
    @Override
    public Object createSubscription(PubsubTopic topic, PubsubSubscription subscriptionName) {
        // Kafka topic creation is implicit on first produce/consume
        return new Object(); 
        // Changed return type
        // TODO altrix: Kafka equivalent handling
    }

    @SneakyThrows
    @Override
    public Object getOrCreateTopic(PubsubTopic topicName) {
        // Kafka topics are auto-created on first use
        return new Object(); 
        // Changed return type
        // TODO altrix: Kafka equivalent handling
    }

    @SneakyThrows
    @Override
    public Object createTopic(PubsubTopic topicName) {
        // Implicit creation, no direct API call needed
        return new Object(); 
        // Changed return type
        // TODO altrix: Kafka equivalent handling
    }

    @Override
    public List<String> testIAMPermissionsOnTopic(PubsubTopic topic, List<String> permissions) {
        // TODO altrix: Kafka equivalent requires manual IAM setup and checking
        return Lists.newArrayList(); 
        // Placeholder, actual implementation needed
    }
}