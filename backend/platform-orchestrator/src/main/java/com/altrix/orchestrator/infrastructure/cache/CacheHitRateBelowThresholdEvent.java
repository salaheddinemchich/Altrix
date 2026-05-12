package com.altrix.orchestrator.infrastructure.cache;

import java.time.Instant;

/**
 * Published by {@link com.altrix.orchestrator.infrastructure.scheduler.CacheHealthScheduler}
 * when the Redis keyspace hit rate drops below the configured threshold (#157).
 */
public record CacheHitRateBelowThresholdEvent(
        double hitRate,
        long hits,
        long misses,
        long evictions,
        Instant detectedAt
) {
    public static CacheHitRateBelowThresholdEvent of(
            double hitRate, long hits, long misses, long evictions) {
        return new CacheHitRateBelowThresholdEvent(hitRate, hits, misses, evictions, Instant.now());
    }
}
