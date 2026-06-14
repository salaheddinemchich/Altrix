package com.example.altrix.pubsub;

/**
 * Enum representing the types of subscriptions in a messaging system.
 * 
 * <p>Note: This enum has been migrated from Google Cloud Pub/Sub to Apache Kafka.
 * In Kafka, the equivalent concepts are:
 * - PULL: Similar to Kafka's consumer polling mechanism.
 * - PUSH: Not directly applicable in Kafka, as it uses a pull-based model.
 *         For push-like behavior, consider using Kafka's built-in triggers or
 *         external message processing triggers.
 */
public enum PubsubSubscriptionType {
    /**
     * Represents a pull subscription type, where the subscriber actively polls for messages.
     * In Kafka, this is analogous to a consumer explicitly polling for messages.
     */
    PULL,
    
    /**
     * Represents a push subscription type. In the context of Kafka, this enum value
     * is retained for compatibility but note that Kafka primarily uses a pull model.
     * For push-like semantics, external triggers or Kafka's built-in mechanisms can be utilized.
     */
    PUSH
}