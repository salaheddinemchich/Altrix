package com.example.altrix.pubsub;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Domain port for publishing to and pulling from Apache Kafka topics.
 * 
 * <p>The implementation lives in {@link PubsubServiceImpl} — kept as an interface 
 * so the messaging backend can be swapped without touching the business code.
 */
public interface PubsubService {
    void publish(PubsubTopic topic, Map<String, String> attributes);
    void publish(PubsubTopic topic, byte[] message);
    void publish(PubsubTopic topic, AltrixPubsubMessage message);
    void publish(PubsubTopic topic, List<AltrixPubsubMessage> altrixMessages);
    void publishPubsubMessages(PubsubTopic topic, Collection<ProducerRecord<String, String>> messages);

    List<ConsumerRecord<String, String>> pull(PubsubSubscription subscription, int maxMessages);
    void acknowledge(PubsubSubscription subscription, List<String> ackIds);

    Subscription getOrCreateSubscription(PubsubTopic topic, PubsubSubscription subscriptionName);
    Subscription createSubscription(PubsubTopic topic, PubsubSubscription subscriptionName);
    // TODO altrix: Kafka does not have direct equivalents for topic and subscription management
    Object getOrCreateTopic(PubsubTopic topicName); // TODO altrix: Kafka topic creation differs
    Object createTopic(PubsubTopic topicName); // TODO altrix: Kafka topic creation differs

    List<String> testIAMPermissionsOnTopic(PubsubTopic topic, List<String> permissions); // TODO altrix: Kafka uses different security model
}