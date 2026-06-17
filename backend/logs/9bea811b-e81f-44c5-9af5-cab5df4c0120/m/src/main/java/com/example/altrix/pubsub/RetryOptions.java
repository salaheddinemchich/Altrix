package com.example.altrix.pubsub;

import lombok.Getter;
import java.util.concurrent.Callable;

/**
 * Fluent retry options for a single {@link Callable} task.
 * 
 * <p>Returned by {@code RetryTask#toRetryOptions()}; consumed by 
 * {@link RetryHandler#execute(RetryOptions)}.
 */
@Getter
public class RetryOptions<R> {
    private final Callable<R> task;
    private int maxAttempts = 3;
    private long delayMillis = 200L;

    public RetryOptions(Callable<R> task) {
        this.task = task;
    }

    public RetryOptions<R> withMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return this;
    }

    public RetryOptions<R> withDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
        return this;
    }
}