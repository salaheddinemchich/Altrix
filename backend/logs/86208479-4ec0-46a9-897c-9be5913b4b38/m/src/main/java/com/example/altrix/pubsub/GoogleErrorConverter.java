package com.example.altrix.pubsub;

import org.apache.kafka.common.KafkaException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Converts Google-specific errors to a standard RuntimeException, adapted for Kafka.
 * 
 * @author [Original Author]
 * @since [Original Version]
 * @see IGoogleErrorConverter
 */
@ApplicationScoped
public class GoogleErrorConverter implements IGoogleErrorConverter {

    /**
     * Converts a Throwable (potentially a KafkaException) into a RuntimeException.
     * 
     * @param cause The Throwable to convert
     * @return A RuntimeException wrapping the cause
     */
    @Override
    public RuntimeException convert(Throwable cause) {
        if (cause instanceof KafkaException kafkaException) {
            // Extract relevant error information from KafkaException
            String errorMessage = kafkaException.getMessage();
            // If nested exception exists, include its message for more context
            if (kafkaException.getCause() != null) {
                errorMessage += " - Nested Cause: " + kafkaException.getCause().getMessage();
            }
            return new RuntimeException("Kafka Error: " + errorMessage, cause);
        }
        // Fallback for unexpected errors not related to Kafka
        return new RuntimeException("Unexpected Error", cause);
    }
}