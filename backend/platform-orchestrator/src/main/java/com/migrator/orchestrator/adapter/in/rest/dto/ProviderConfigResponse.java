package com.migrator.orchestrator.adapter.in.rest.dto;

import com.migrator.orchestrator.domain.port.in.ProviderConfigView;
import com.migrator.orchestrator.infra.ai.provider.ProviderCostTier;

import java.time.Instant;

/**
 * Outbound REST response for a single provider's effective configuration.
 * The API key is NEVER included — only {@code hasCustomApiKey} signals
 * that a user-supplied key override is stored.
 */
public record ProviderConfigResponse(
        String           providerId,
        ProviderCostTier costTier,
        boolean          effectiveEnabled,
        boolean          hasCustomApiKey,
        String           effectiveModelAnalysis,
        String           effectiveModelMigration,
        Instant          updatedAt
) {
    public static ProviderConfigResponse from(ProviderConfigView view) {
        return new ProviderConfigResponse(
                view.providerId(),
                view.costTier(),
                view.effectiveEnabled(),
                view.hasCustomApiKey(),
                view.effectiveModelAnalysis(),
                view.effectiveModelMigration(),
                view.updatedAt()
        );
    }
}
