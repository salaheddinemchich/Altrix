package com.altrix.orchestrator.domain.model;

import java.time.Instant;

/**
 * Immutable record of token consumption for a single AI provider call.
 * {@code tier} is stored as a plain string (e.g. "ANALYSIS", "MIGRATION")
 * to keep the domain layer free of infrastructure types.
 */
public record TokenUsageRecord(
        String  providerId,
        String  tier,
        int     inputTokens,
        int     outputTokens,
        int     totalTokens,
        Instant recordedAt
) {}
