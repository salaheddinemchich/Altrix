package com.example.altrix.pubsub;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class RetryHandler {

    /**
     * Execute the wrapped task, retrying up to {@code maxAttempts} times.
     * Exceptions are unwrapped and rethrown as runtime exceptions so callers
     * don't have to wrap calls in try/catch.
     */
    public <R> R execute(RetryOptions<R> options) {
        Throwable last = null;
        for (int attempt = 1; attempt <= options.getMaxAttempts(); attempt++) {
            try {
                return options.getTask().call();
            } catch (Throwable t) {
                last = t;
                log.warn("Kafka task attempt {}/{} failed: {}", attempt, options.getMaxAttempts(), t.getMessage());
                if (attempt < options.getMaxAttempts()) {
                    try {
                        Thread.sleep(options.getDelayMillis());
                    } catch (Exception ie) { // Changed to catch Exception
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying Kafka task", ie); // Changed to RuntimeException
                    }
                }
            }
        }
        if (last instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException("Kafka task exhausted retries", last); // Changed to RuntimeException
    }
}