package com.example.altrix.pubsub;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * CDI producer for the singleton {@link KafkaProducer} used by the publisher.
 * 
 * <p>This producer creates a Kafka producer instance configured with default settings.
 * It is designed to work with the application's Kafka configuration.
 */
@Slf4j
@ApplicationScoped
public class PubsubClientProducer {

    /**
     * Produced as a {@code @Singleton} (pseudo-scope) to ensure a single Kafka producer instance
     * is shared across the application.
     */
    @Produces
    @Singleton
    @SneakyThrows
    public KafkaProducer<String, String> createKafkaProducer() {
        log.info("Initialising Kafka producer client");
        
        // Basic Kafka producer configuration with string serializers for keys and values
        var props = new java.util.Properties();
        props.put("bootstrap.servers", "localhost:9092"); // Default bootstrap servers
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        
        // Create and return the Kafka producer instance
        return new KafkaProducer<>(props);
    }
}