package com.example.orders;

/**
 * Kafka topic and consumer group names used across the app.
 * A single anchor for Kafka configuration.
 */
public final class PubSubConfig {
    /**
     * Topic orders are published to.
     */
    public static final String ORDERS_TOPIC = "orders.created";
    
    /**
     * Consumer group for the order processor.
     */
    public static final String ORDERS_CONSUMER_GROUP = "orders.created.processor";
    
    private PubSubConfig() { }
}