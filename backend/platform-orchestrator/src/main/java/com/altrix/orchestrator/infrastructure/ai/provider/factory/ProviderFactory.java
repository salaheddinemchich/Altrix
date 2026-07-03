package com.altrix.orchestrator.infrastructure.ai.provider.factory;

import com.altrix.orchestrator.infrastructure.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infrastructure.ai.provider.RegisteredProvider;

/**
 * SPI for registering an AI provider.
 *
 * <p>Each concrete implementation is a Spring {@code @Component} responsible
 * for exactly one provider. {@link com.altrix.orchestrator.infrastructure.ai.ProviderRegistry}
 * discovers all implementations via {@code List<ProviderFactory>} auto-injection —
 * adding a new provider never requires touching the registry (Open/Closed Principle).
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Validate their config eagerly in the constructor (fail-fast at startup).</li>
 *   <li>Never log API key values — only log {@code [CONFIGURED]} or {@code [NOT SET]}.</li>
 *   <li>Return {@code false} from {@link #isEnabled()} when the provider should be skipped.</li>
 * </ul>
 */
public interface ProviderFactory {

    /**
     * Stable identifier used in logs, CB registry, and routing config.
     */
    String providerId();

    /**
     * Whether this provider should participate in the routing pool.
     */
    boolean isEnabled();

    /**
     * Cost classification — used by {@code TierPreferenceStrategy} to order providers.
     */
    ProviderCostTier costTier();

    /**
     * System-default model name for analysis-grade calls (may be overridden via DB config).
     */
    String defaultModelAnalysis();

    /**
     * System-default model name for migration-grade calls (may be overridden via DB config).
     */
    String defaultModelMigration();

    /**
     * Builds the {@link RegisteredProvider} for this provider.
     * Called only when {@link #isEnabled()} is {@code true}.
     * May be called more than once when the provider config is refreshed at runtime.
     */
    RegisteredProvider build();
}
