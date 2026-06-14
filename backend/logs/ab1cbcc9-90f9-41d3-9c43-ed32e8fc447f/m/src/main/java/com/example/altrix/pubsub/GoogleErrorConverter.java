package com.example.altrix.pubsub;

import org.apache.kafka.common.KafkaException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Converts Kafka client exceptions to internal runtime exceptions.
 * 
 * <p>Kept as an implementation (with potential for indirection in the service) 
 * so test code can stub it without bringing in Kafka clients.
 */
@ApplicationScoped
public class GoogleErrorConverter implements IGoogleErrorConverter {
    
    public RuntimeException convert(Throwable cause) {
        if (cause instanceof KafkaException k) {
            return new RuntimeException("Kafka error: " + k.getMessage(), cause);
        }
        return new RuntimeException("Unexpected Kafka error", cause);
    }
}