package com.altrix.orchestrator.infra.ai.provider;

/**
 * Classifies a provider by billing model.
 * Distinct from {@link ProviderTier} which describes call quality (ANALYSIS vs MIGRATION).
 */
public enum ProviderCostTier {
    FREE,
    PAID
}
