package com.example.altrix.pubsub;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import com.example.altrix.pubsub.PubsubTopic;
import com.example.altrix.pubsub.PubsubSubscription;
import com.example.altrix.pubsub.AltrixPubsubMessage;
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
import org.apache.kafka.clients.admin.AdminClient;
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

    static { 
        // Initialize Kafka Producer and Consumer with default config 
        // (Assuming config is set via application.properties or similar)
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "altrix-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"your_username\" password=\"your_password\";");
        props.put("sasl.mechanism", "PLAIN");
        props.put(ConsumerConfig.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        kafkaConsumer = new KafkaConsumer<>(props);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
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
        publish(topic, List.of(message));
    }

    @Override
    public void publish(PubsubTopic topic, List<AltrixPubsubMessage> altrixMessages) {
        List<String> kafkaMessages = new java.util.ArrayList<>();
        for (AltrixPubsubMessage altrixMessage : altrixMessages) {
            byte[] messageData = altrixMessage.getMessage();
            // Removed getAttributes() call as it's not defined in AltrixPubsubMessage
            // Map<String, String> attributes = altrixMessage.getAttributes();
            String orderingKey = altrixMessage.getOrderingKey();
            if (messageData != null) { 
                // Simplified for demo; in practice, handle attributes and ordering key as needed
                String messageStr = new String(messageData);
                kafkaMessages.add(messageStr);
            } else {
                log.error("Message data is empty.");
            }
        }
        publishKafkaMessages(topic, kafkaMessages);
    }

    @Override
    public void publishKafkaMessages(PubsubTopic topic, Collection<String> kafkaMessages) {
        if (!kafkaMessages.isEmpty()) {
            kafkaMessages.forEach(message -> {
                try {
                    kafkaProducer.send(new ProducerRecord<>(topic.getFullTopicName(), message));
                } catch (KafkaException e) {
                    log.error("Error sending message to Kafka", e);
                }
            });
        }
    }

    @Override
    public List<String> pull(PubsubSubscription subscription, int maxMessages) {
        List<String> messages = new java.util.ArrayList<>();
        // Replaced with a direct string representation (assuming a meaningful toString isn't available)
        kafkaConsumer.subscribe(List.of(subscription.toString()));
        try {
            kafkaConsumer.poll(java.time.Duration.ofSeconds(1)).forEach(record -> messages.add(record.value()));
            // Simple demo; in a real app, handle partitions, offsets, and errors properly
            kafkaConsumer.commitSync();
        } catch (KafkaException e) {
            log.error("Error pulling from Kafka", e);
        } finally {
            kafkaConsumer.unsubscribe();
        }
        return messages;
    }

    @Override
    public void acknowledge(PubsubSubscription subscription, List<String> offsets) {
        // Kafka acknowledges are typically handled automatically or via commitSync/Async
        // This method's implementation may vary based on your acknowledgment strategy
        kafkaConsumer.commitSync();
    }

    @Override
    public void getOrCreateSubscriptionKafka(PubsubTopic topic, PubsubSubscription subscriptionName) {
        // Kafka does not have an explicit "subscription" concept like Pub/Sub
        // This method is a placeholder; you might remove or adapt it based on your needs
        log.info("Kafka does not require explicit subscription creation for {}", subscriptionName);
    }

    @Override
    public void createSubscriptionKafka(PubsubTopic topic, PubsubSubscription subscriptionName) {
        // Same as above; Kafka's consumer groups implicitly "subscribe" to topics
        log.info("Kafka subscription creation not applicable for {}", subscriptionName);
    }

    @Override
    public void getOrCreateTopicKafka(PubsubTopic topicName) {
        // Kafka topics are auto-created when first produced to or consumed from
        log.info("Kafka topic {} will be auto-created on first use", topicName);
    }

    @Override
    public void createTopicKafka(PubsubTopic topicName) {
        // Explicit topic creation is not typically done in code for Kafka
        // Use the Kafka CLI or AdminClient for explicit creations if needed
        log.info("Explicit Kafka topic creation not implemented; use Kafka AdminClient if necessary");
    }

    @Override
    public List<String> testKafkaPermissionsOnTopic(PubsubTopic topic, List<String> permissions) {
        // Kafka permission testing involves checking ACLs, which is complex and 
        // typically done externally. This method is a placeholder.
        log.info("Kafka permission testing is not implemented; use Kafka CLI or AdminClient");
        return Collections.emptyList();
    }
}