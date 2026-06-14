package com.example.altrix.pubsub;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Domain port for publishing to and pulling from Apache Kafka topics.
 * 
 * <p>The implementation lives in {@link PubsubServiceImpl} — kept as an interface 
 * so the messaging backend can be swapped without touching the business code.
 */
public interface PubsubService {
    void publish(String topic, Map<String, String> attributes, String message);
    void publish(String topic, byte[] message);
    void publish(String topic, ProducerRecord<String, String> message);
    void publish(String topic, List<ProducerRecord<String, String>> messages);
    void publishKafkaMessages(String topic, Collection<String> messages);
    List<ConsumerRecord<String, String>> pull(String subscriptionGroup, String topic, int maxMessages);
    void acknowledge(String subscriptionGroup, String topic, List<TopicPartition> offsets);
    // Kafka consumer groups are declared client-side via group.id, no direct equivalent
    void getOrCreateConsumerGroup(String subscriptionGroup);
    // Kafka topics are auto-created on first produce/consume, or use AdminClient for explicit creation
    void getOrCreateTopic(String topicName);
    void createTopic(String topicName, short partitions, short replicationFactor);
    // TODO altrix: No direct Kafka equivalent for IAM permission testing; use AdminClient ACLs or external IAM
    List<String> testSecurityPermissionsOnTopic(String topic, List<String> permissions);
    KafkaProducer<String, String> getKafkaProducer();
    KafkaConsumer<String, String> getKafkaConsumer(String groupId);
}