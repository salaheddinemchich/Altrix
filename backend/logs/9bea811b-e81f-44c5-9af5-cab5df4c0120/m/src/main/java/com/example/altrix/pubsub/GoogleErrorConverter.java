package com.example.altrix.pubsub;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.KafkaException;

/**
 * Converts Kafka client exceptions to internal runtime exceptions.
 */
@ApplicationScoped
public class GoogleErrorConverter implements IGoogleErrorConverter {

    @Override
    public RuntimeException convert(Throwable cause) {
        if (cause instanceof KafkaException k) {
            return new IllegalStateException("Kafka error: " + k.getMessage(), cause);
        }
        return new IllegalStateException("Unexpected Kafka error", cause);
    }
}