package com.altrix.orchestrator.domain.model;

/**
 * Aggregated token and cost summary per agent+provider combination (#53).
 *
 * <p>Returned by {@link com.altrix.orchestrator.domain.port.out.AiCallLedgerPort#queryUsage}
 * and exposed via the billing REST endpoint.
 */
public record AiCallUsageSummary(
        String agentName,
        String providerName,
        long totalInputTokens,
        long totalOutputTokens,
        double totalCostUsd,
        long callCount
) {
}
