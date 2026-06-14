package com.example.altrix.pubsub;

import com.example.altrix.pubsub.PubsubTopic;
import com.example.altrix.pubsub.PubsubSubscription;
import com.example.altrix.pubsub.AltrixPubsubMessage;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
    void publishKafkaMessages(PubsubTopic topic, Collection<String> kafkaMessages);
    List<String> pull(PubsubSubscription subscription, int maxMessages);
    void acknowledge(PubsubSubscription subscription, List<String> offsets);
    // TODO altrix: Kafka equivalent for getOrCreateSubscription (manual admin client setup required)
    void getOrCreateSubscriptionKafka(PubsubTopic topic, PubsubSubscription subscriptionName);
    // TODO altrix: Kafka equivalent for createSubscription (manual admin client setup required)
    void createSubscriptionKafka(PubsubTopic topic, PubsubSubscription subscriptionName);
    // TODO altrix: Kafka topic creation is auto-handled by Kafka, no explicit API call needed
    void getOrCreateTopicKafka(PubsubTopic topicName);
    // TODO altrix: Kafka topic creation is auto-handled by Kafka, no explicit API call needed
    void createTopicKafka(PubsubTopic topicName);
    // TODO altrix: IAM Permissions testing not directly applicable to Kafka (use ACLs or external IAM)
    List<String> testKafkaPermissionsOnTopic(PubsubTopic topic, List<String> permissions);
}