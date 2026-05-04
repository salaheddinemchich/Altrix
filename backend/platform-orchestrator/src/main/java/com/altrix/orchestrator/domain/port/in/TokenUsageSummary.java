package com.altrix.orchestrator.domain.port.in;

/** Aggregated token-usage stats for one provider+tier combination. */
public record TokenUsageSummary(
        String providerId,
        String tier,
        long   inputTokens,
        long   outputTokens,
        long   totalTokens,
        long   callCount
) {}
