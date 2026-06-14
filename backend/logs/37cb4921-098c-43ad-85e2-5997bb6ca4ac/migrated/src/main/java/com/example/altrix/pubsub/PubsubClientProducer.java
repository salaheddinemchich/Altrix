package com.example.altrix.pubsub;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * CDI producer for the singleton {@link KafkaProducer} used by the publisher.
 * Configured to connect to Apache Kafka.
 *
 * <p>If Kafka configuration is incomplete (typical in CI / sandbox boot-health
 * runs where the test rig has no Kafka setup), the producer still returns a
 * usable {@link KafkaProducer} instance — any actual produce call will fail
 * at execution time, but the application boots cleanly and non-Kafka endpoints
 * (e.g., {@code /api/health}) keep working.
 */
@Slf4j
@ApplicationScoped
public class PubsubClientProducer {

    /**
     * Produced as a {@code @Singleton} (pseudo-scope) to ensure a single
     * Kafka producer instance for the application.
     */
    @Produces
    @Singleton
    @SneakyThrows
    public KafkaProducer<String, String> createKafkaProducer() {
        log.info("Initialising Apache Kafka producer client");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092"); // Default bootstrap servers
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        // Attempt to load custom Kafka config from properties (if present)
        // For example, from application.properties/yaml:
        // spring.kafka.bootstrap-servers=<custom-bootstrap-servers>
        // Here, we directly use the default for demonstration; in a real app,
        // you'd load these dynamically from config.
        return new KafkaProducer<>(props);
    }
}