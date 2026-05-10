package com.altrix.orchestrator.domain.port.in;

/**
 * Aggregated token-usage stats for one provider+tier combination.
 */
public record TokenUsageSummary(
        String providerId,
        String tier,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long callCount,
        /** Estimated USD cost. 0.0 when no pricing config is available for this provider. */
        double estimatedCostUsd
) {
    /**
     * Convenience constructor without pricing — cost defaults to 0.
     */
    public TokenUsageSummary(String providerId, String tier,
                             long inputTokens, long outputTokens,
                             long totalTokens, long callCount) {
        this(providerId, tier, inputTokens, outputTokens, totalTokens, callCount, 0.0);
    }
}
