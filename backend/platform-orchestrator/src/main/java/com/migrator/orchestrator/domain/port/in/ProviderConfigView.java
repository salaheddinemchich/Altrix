package com.migrator.orchestrator.domain.port.in;

import com.migrator.orchestrator.infra.ai.provider.ProviderCostTier;

import java.time.Instant;

/**
 * Read model returned by {@link GetProviderConfigsUseCase}.
 *
 * <p>The API key is NEVER included — only {@code hasCustomApiKey} indicates
 * whether a key override is stored.
 */
public record ProviderConfigView(
        String           providerId,
        ProviderCostTier costTier,
        boolean          effectiveEnabled,
        boolean          hasCustomApiKey,
        String           effectiveModelAnalysis,
        String           effectiveModelMigration,
        Instant          updatedAt
) {}
