package com.example.altrix.pubsub;

/**
 * Converts Kafka client exceptions to internal runtime exceptions.
 * 
 * <p>Kept as an interface (with a {@link jakarta.inject.Provider} indirection in the 
 * service) so test code can stub it without bringing in the Kafka client.
 */
public interface IGoogleErrorConverter {
    /**
     * Converts a Kafka client exception to an internal runtime exception.
     * 
     * @param cause The Kafka client exception to convert
     * @return An internal runtime exception representing the cause
     */
    RuntimeException convert(Throwable cause);
}