package com.example.altrix.pubsub;

/**
 * Converts exceptions to internal runtime exceptions, adapting to Kafka's error model.
 * 
 * <p>Kept as an interface (with a {@link jakarta.inject.Provider} indirection in the 
 * service) so test code can stub it without bringing in Kafka dependencies.
 */
public interface IGoogleErrorConverter {
    RuntimeException convert(Throwable cause);
}