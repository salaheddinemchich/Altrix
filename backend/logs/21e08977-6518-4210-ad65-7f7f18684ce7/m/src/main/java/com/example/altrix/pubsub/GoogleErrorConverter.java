package com.example.altrix.pubsub;

import org.apache.kafka.common.KafkaException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Converts Kafka-specific exceptions into a standard RuntimeException for error handling.
 * <p>
 * This converter focuses on KafkaException, which is the base class for all Kafka-related errors.
 * If the exception is not a KafkaException, it falls back to a generic "Unexpected Kafka error".
 */
@ApplicationScoped
public class GoogleErrorConverter implements IGoogleErrorConverter {
    @Override
    public RuntimeException convert(Throwable cause) {
        if (cause instanceof KafkaException kafkaException) {
            // Extract relevant details from the KafkaException for the error message
            String errorMessage = "Kafka error [" + kafkaException.getClass().getSimpleName() + "]: " + kafkaException.getMessage();
            return new RuntimeException(errorMessage, kafkaException);
        }
        // For any other exception, provide a generic message with the cause
        return new RuntimeException("Unexpected Kafka error", cause);
    }
}