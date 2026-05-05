package com.altrix.orchestrator.domain.port.in;

import java.util.Map;

/**
 * Snapshot of the resilience subsystem — circuit breaker states and per-tier
 * bulkhead capacity for the admin dashboard (#147).
 */
public record ProviderResilienceStatus(
        /** provider-id → CB state name (CLOSED / OPEN / HALF_OPEN) */
        Map<String, String> circuitBreakers,
        /** tier-name (ANALYSIS / MIGRATION) → bulkhead snapshot */
        Map<String, BulkheadSnapshot> bulkheads
) {
    public record BulkheadSnapshot(int maxConcurrent, int availablePermits) {}
}
