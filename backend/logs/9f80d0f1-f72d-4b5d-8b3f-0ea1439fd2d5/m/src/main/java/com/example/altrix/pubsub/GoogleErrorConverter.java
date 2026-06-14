package com.example.altrix.pubsub;

import org.apache.kafka.common.KafkaException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Converts Kafka-specific exceptions into a standard RuntimeException for easier handling.
 * <p>
 * NOTE: This converter now targets Kafka exceptions. Original Pub/Sub error handling has been adapted.
 * For Kafka-specific errors, see https://kafka.apache.org/31/javadoc/org/apache/kafka/common/KafkaException.html
 */
@ApplicationScoped
public class GoogleErrorConverter implements IGoogleErrorConverter {
    @Override
    public RuntimeException convert(Throwable cause) {
        if (cause instanceof KafkaException k) {
            // Extract relevant error details if available (e.g., for Producer/Consumer exceptions)
            String errorMsg = k.getMessage() != null ? k.getMessage() : "Unknown Kafka Error";
            return new KafkaException("Kafka Error: " + errorMsg, cause);
        }
        // Fallback for unexpected errors not directly related to Kafka
        return new KafkaException("Unexpected Error in Messaging System", cause);
    }
}