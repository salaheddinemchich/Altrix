package com.example.altrix.pubsub;

/**
 * Converts Kafka client exceptions to internal runtime exceptions.
 *
 * <p>Kept as an interface (with a {@link jakarta.inject.Provider} indirection in the
 * service) so test code can stub it without bringing in Kafka dependencies.
 */
public interface IGoogleErrorConverter {
    RuntimeException convert(Throwable cause);
}