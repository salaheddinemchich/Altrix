package com.example.altrix.pubsub;

import org.apache.kafka.common.KafkaException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Converts Kafka-specific exceptions into a standard RuntimeException for the application.
 * <p>
 * This converter handles KafkaException and its subclasses, providing a basic error message.
 * For more specific error handling, consider adding checks for particular Kafka exception types.
 */
@ApplicationScoped
public class GoogleErrorConverter implements IGoogleErrorConverter {

    @Override
    public RuntimeException convert(Throwable cause) {
        if (cause instanceof KafkaException kafkaException) {
            // Extract relevant error details if available (e.g., for specific Kafka exceptions)
            String errorMessage = kafkaException.getMessage() != null ? kafkaException.getMessage() : "Unknown Kafka error";
            return new IllegalStateException("Kafka error: " + errorMessage, cause);
        } else {
            // Fallback for unexpected errors not related to Kafka
            return new IllegalStateException("Unexpected error", cause);
        }
    }
}