package com.example.altrix.pubsub;

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
  void publishPubsubMessages(PubsubTopic topic, Collection<String> messages);
  // Changed to String for Kafka
  List<String> pull(PubsubSubscription subscription, int maxMessages);
  // Changed to String for Kafka
  void acknowledge(PubsubSubscription subscription, List<String> ackIds);
  PubsubSubscription getOrCreateSubscription(PubsubTopic topic, PubsubSubscription subscriptionName);
  PubsubSubscription createSubscription(PubsubTopic topic, PubsubSubscription subscriptionName);
  PubsubTopic getOrCreateTopic(PubsubTopic topicName);
  PubsubTopic createTopic(PubsubTopic topicName);
  List<String> testIAMPermissionsOnTopic(PubsubTopic topic, List<String> permissions);
}