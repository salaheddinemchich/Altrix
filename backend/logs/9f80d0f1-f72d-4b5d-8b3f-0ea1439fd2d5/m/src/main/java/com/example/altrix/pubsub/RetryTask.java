package com.example.altrix.pubsub;

import java.util.concurrent.Callable;

/**
 * Base class for retry-capable Kafka tasks. Subclasses implement 
 * {@link #call()} with the underlying Kafka operation.
 */
public abstract class RetryTask<R> implements Callable<R> {
    public RetryOptions<R> toRetryOptions() {
        return new RetryOptions<>(this);
    }
}