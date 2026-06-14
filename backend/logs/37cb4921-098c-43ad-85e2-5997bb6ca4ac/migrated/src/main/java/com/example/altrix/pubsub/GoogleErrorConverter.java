package com.example.altrix.pubsub;

import org.apache.kafka.common.KafkaException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Converts Google-specific errors to a standard RuntimeException, adapted for Kafka.
 * 
 * @author [Original Author]
 * @since [Original Version]
 * @modified [Current Date] - Adapted for Apache Kafka
 */
@ApplicationScoped
public class GoogleErrorConverter implements IGoogleErrorConverter {

    /**
     * Converts a Throwable to a RuntimeException, handling Kafka-specific exceptions.
     * 
     * @param cause the Throwable to convert
     * @return a RuntimeException wrapping the cause
     */
    @Override
    public RuntimeException convert(Throwable cause) {
        if (cause instanceof KafkaException k) {
            // Extract relevant error details if available (e.g., for KafkaException subclasses)
            String errorMessage = k.getMessage() != null ? k.getMessage() : "Unknown Kafka Error";
            return new KafkaException("Kafka Error: " + errorMessage, cause);
        }
        // Fallback for other unexpected errors (maintains original behavior for non-Kafka errors)
        return new KafkaException("Unexpected Error", cause);
    }
}