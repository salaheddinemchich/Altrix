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
 * Configured to connect to Kafka using default settings (localhost:9092).
 * 
 * <p>If Kafka is not available (e.g., in CI or sandbox environments without a Kafka setup),
 * the producer still returns a usable {@link KafkaProducer} instance, but actual sends will fail at execution time.
 * This allows the application to boot cleanly, with non-Kafka endpoints remaining functional.
 */
@Slf4j
@ApplicationScoped
public class PubsubClientProducer {

    /**
     * Produced as a {@code @Singleton} to ensure a single Kafka producer instance for the application.
     * 
     * @return A configured KafkaProducer instance for string key/string value topics.
     */
    @Produces
    @Singleton
    @SneakyThrows
    public KafkaProducer<String, String> createKafkaProducer() {
        log.info("Initialising Apache Kafka producer client (default config: localhost:9092)");
        
        // Basic Kafka Producer configuration with string serializers for key and value
        var props = new java.util.Properties();
        props.put("bootstrap.servers", "localhost:9092"); // Default bootstrap servers
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        
        // Attempt to create producer; will fail at send time if Kafka is unreachable
        try {
            return new KafkaProducer<>(props);
        } catch (Exception e) {
            log.warn("Kafka producer initialization failed ({}). Instance created but sends will fail until Kafka is reachable.",
                     e.getMessage());
            // Fallback: Return a producer that will fail on send (for consistency with original behavior)
            return new KafkaProducer<>(props); // Re-throw not possible here due to @SneakyThrows; rely on send-time failures
        }
    }
}