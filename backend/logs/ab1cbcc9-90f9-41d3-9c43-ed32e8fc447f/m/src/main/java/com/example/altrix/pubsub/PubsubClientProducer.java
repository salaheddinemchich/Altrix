package com.example.altrix.pubsub;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;
/**
 * CDI producer for the singleton {@link KafkaProducer} used by the publisher.
 * Configured to connect to Apache Kafka.
 */
@Slf4j
@ApplicationScoped
public class PubsubClientProducer {

    /**
     * Produced as a {@code @Singleton} (pseudo-scope) to ensure a single Kafka producer instance.
     * 
     * @return KafkaProducer instance configured for string keys and values
     */
    @Produces
    @Singleton
    @SneakyThrows
    public KafkaProducer<String, String> createKafkaProducer() {
        log.info("Initialising Apache Kafka producer");
        
        // Basic Kafka producer configuration (adjust as needed based on application.properties)
        var props = new java.util.Properties();
        props.put("bootstrap.servers", "localhost:9092"); // Default; override via application.properties
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        
        // Initialize and return the Kafka producer
        return new KafkaProducer<>(props);
    }
}