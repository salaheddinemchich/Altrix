package com.example.altrix.pubsub;

/**
 * Enum representing the type of subscription in a messaging system.
 * 
 * @author [Original Author]
 * @since [Original Version]
 * @note Migrated from GCP Pub/Sub to Apache Kafka. 
 *       In Kafka, subscriptions are not explicitly defined like in Pub/Sub; 
 *       instead, consumer groups implicitly subscribe to topics. 
 *       This enum is retained for compatibility but its usage may need adjustment.
 */
public enum PubsubSubscriptionType {
    /**
     * Represents a pull-based subscription where messages are fetched by the subscriber.
     * <p>
     * **Kafka Equivalent:** Consumer actively polling the topic for new messages.
     */
    PULL,
    
    /**
     * Represents a push-based subscription where messages are sent to the subscriber.
     * <p>
     * **Kafka Note:** Direct equivalent does not exist. 
     *               Push logic must be implemented externally (e.g., using a Kafka consumer 
     *               in a service that then pushes messages to subscribers).
     */
    PUSH
}