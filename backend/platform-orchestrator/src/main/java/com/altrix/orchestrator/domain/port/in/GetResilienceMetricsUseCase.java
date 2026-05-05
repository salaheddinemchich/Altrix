package com.altrix.orchestrator.domain.port.in;

/** Driving port — returns a live snapshot of the AI resilience subsystem. */
public interface GetResilienceMetricsUseCase {
    ProviderResilienceStatus getResilienceStatus();
}
