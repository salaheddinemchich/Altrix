package com.example.altrix.pubsub;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.resource.ResourcePattern;
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

    void publish(String topic, Map<String, String> attributes);
    void publish(String topic, byte[] message);
    void publish(String topic, AltrixPubsubMessage message);
    void publish(String topic, List<AltrixPubsubMessage> altrixMessages);
    void publishKafkaMessages(String topic, Collection<String> messages);

    // Kafka consumer groups replace named subscriptions
    void subscribe(String topic, String groupId);
    List<String> pull(String topic, String groupId, int maxMessages);
    void acknowledge(String topic, String groupId, List<String> offsets);

    // Kafka topic creation via AdminClient
    void getOrCreateTopic(String topicName, short partitions, short replicas);
    void createTopic(String topicName, short partitions, short replicas);

    // Kafka does not manage subscriptions as resources; use group.id for consumer groups
    void getOrCreateConsumerGroup(String groupId);
    void createConsumerGroup(String groupId);

    // No direct Kafka equivalent for Pub/Sub IAM permission testing
    List<String> testTopicPermissions(String topic, List<String> permissions);

    // Kafka ACLs can be managed via AdminClient, but this requires manual implementation
    void testAclPermissions(String topic, List<AclOperation> operations);

    // Helper for producing/consuming configuration
    Properties getKafkaProducerConfig();
    Properties getKafkaConsumerConfig(String groupId);
}