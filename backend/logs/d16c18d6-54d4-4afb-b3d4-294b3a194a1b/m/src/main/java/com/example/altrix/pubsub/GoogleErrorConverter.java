package com.example.altrix.pubsub;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.common.KafkaException;

/**
 * Converts Kafka client exceptions to internal runtime exceptions.
 */
@ApplicationScoped
public class GoogleErrorConverter implements IGoogleErrorConverter {

    @Override
    public RuntimeException convert(Throwable cause) {
        if (cause instanceof KafkaException ke) {
            return new RuntimeException("Kafka error: " + ke.getMessage(), cause);
        }
        return new RuntimeException("Unexpected error", cause);
    }
}