package com.example.altrix.pubsub;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.util.Collection;
import java.util.Collections;
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

    void publish(String topic, AltrixKafkaMessage message);

    void publish(String topic, List<AltrixKafkaMessage> messages);

    void publishKafkaMessages(String topic, Collection<ProducerRecord<String, String>> records);

    ConsumerRecords<String, String> pull(String subscriptionGroup, String topic, int maxMessages);

    void acknowledge(String subscriptionGroup, List<TopicPartition> offsets);

    String getOrCreateTopic(String topicName);

    String createTopic(String topicName);

    List<String> testACLPermissionsOnTopic(String topic, List<String> permissions);
}