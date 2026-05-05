package com.altrix.orchestrator.domain.model;

import java.time.Instant;

/**
 * Immutable record of a single AI provider call for cost and usage auditing (#53).
 *
 * <p>Recorded by {@link com.altrix.orchestrator.infra.ai.ProviderRouter} immediately
 * after every {@code ChatLanguageModel.generate()} returns.
 */
public record AiCallLedgerEntry(
        String  jobId,
        String  agentName,
        String  providerName,
        String  modelName,
        String  tier,
        long    inputTokens,
        long    outputTokens,
        double  costUsd,
        boolean cacheHit,
        Instant createdAt
) {}
