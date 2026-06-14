package com.example.altrix.pubsub;

/**
 * Converts exceptions from messaging services to internal runtime exceptions.
 * 
 * <p>Interface for potential indirection or stubbing in testing scenarios.
 */
public interface IGoogleErrorConverter {
    RuntimeException convert(Throwable cause);
}