package com.example.altrix.common;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Topic names used across the application. Centralised so 
 * the migration agent has a single anchor to update when porting to Kafka.
 */
public final class PubsubConfig {

    public static final String PROJECT_ID = System.getProperty("kafka.cluster.id", System.getenv().getOrDefault("KAFKA_CLUSTER_ID", "altrix-local"));
    
    // Kafka topic names (equivalent to former Pub/Sub topics)
    public static final String ORDERS_CREATED_TOPIC = "orders.created";
    public static final String PAYMENTS_COMPLETED_TOPIC = "payments.completed";
    
    // Kafka consumer group for orders.created topic (equivalent to former subscription)
    public static final String ORDERS_CREATED_CONSUMER_GROUP = "payment-svc-group";
    
    // Kafka configuration defaults
    public static final String KAFKA_BOOTSTRAP_SERVERS = System.getProperty("kafka.bootstrap.servers", System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
    public static final String KEY_SERIALIZER = StringSerializer.class.getName();
    public static final String VALUE_SERIALIZER = StringSerializer.class.getName();
    public static final String KEY_DESERIALIZER = StringDeserializer.class.getName();
    public static final String VALUE_DESERIALIZER = StringDeserializer.class.getName();
    
    private PubsubConfig() {}
}