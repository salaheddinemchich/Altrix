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
 * Configured to connect to Apache Kafka.
 */
@Slf4j
@ApplicationScoped
public class PubsubClientProducer {

    /**
     * Produced as a {@code @Singleton} (pseudo-scope) to ensure a single Kafka producer instance.
     */
    @Produces
    @Singleton
    @SneakyThrows
    public KafkaProducer<String, String> createKafkaProducer() {
        log.info("Initialising Apache Kafka producer");
        
        // Kafka producer configuration (example, adjust as needed)
        var props = new java.util.Properties();
        props.put("bootstrap.servers", "localhost:9092"); // Default bootstrap server
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        return new KafkaProducer<>(props);
    }
}