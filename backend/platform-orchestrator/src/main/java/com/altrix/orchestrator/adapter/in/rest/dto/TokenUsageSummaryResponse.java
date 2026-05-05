package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.port.in.TokenUsageSummary;

public record TokenUsageSummaryResponse(
        String providerId,
        String tier,
        long   inputTokens,
        long   outputTokens,
        long   totalTokens,
        long   callCount,
        double estimatedCostUsd
) {
    public static TokenUsageSummaryResponse from(TokenUsageSummary s) {
        return new TokenUsageSummaryResponse(
                s.providerId(), s.tier(),
                s.inputTokens(), s.outputTokens(), s.totalTokens(),
                s.callCount(), s.estimatedCostUsd());
    }
}
