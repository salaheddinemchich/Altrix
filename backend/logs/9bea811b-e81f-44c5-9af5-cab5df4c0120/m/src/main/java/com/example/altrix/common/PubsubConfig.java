package com.example.altrix.common;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;

/**
 * Topic names used across the application. Centralised so 
 * the migration agent has a single anchor to update when porting to Kafka.
 */
public final class PubsubConfig {

    public static final String PROJECT_ID = System.getProperty("kafka.cluster.id", System.getenv().getOrDefault("KAFKA_CLUSTER_ID", "altrix-local"));
    
    // Kafka topic names (equivalent to former Pub/Sub topics)
    public static final String ORDERS_CREATED_TOPIC = "orders.created";
    public static final String PAYMENTS_COMPLETED_TOPIC = "payments.completed";
    
    // Kafka consumer group for orders.created (equivalent to former subscription)
    public static final String ORDERS_CREATED_CONSUMER_GROUP = "payment-svc";
    public static final int ORDERS_CREATED_ACK_TIMEOUT_SECONDS = 30; // Former pull subscription ack deadline

    private PubsubConfig() {}
}