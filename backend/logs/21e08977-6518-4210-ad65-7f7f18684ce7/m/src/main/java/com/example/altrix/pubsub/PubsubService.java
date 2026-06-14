package com.example.altrix.pubsub;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;

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
    void publishMessages(String topic, Collection<ProducerRecord<String, String>> messages);
    ConsumerRecords<String, String> pull(String subscription, int maxMessages);
    void acknowledge(String subscription, List<String> ackIds);
    // TODO altrix: Kafka doesn't have direct equivalents for subscription/topic management or IAM testing
    String getOrCreateSubscription(String topic, String subscriptionName);
    String createSubscription(String topic, String subscriptionName);
    String getOrCreateTopic(String topicName);
    String createTopic(String topicName);
    List<String> testIAMPermissionsOnTopic(String topic, List<String> permissions);
}