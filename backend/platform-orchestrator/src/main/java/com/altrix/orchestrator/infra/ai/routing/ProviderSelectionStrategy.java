package com.altrix.orchestrator.infra.ai.routing;

import com.altrix.orchestrator.infra.ai.provider.ProviderTier;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;

import java.util.List;

/**
 * Strategy that determines the order in which providers are tried for a call.
 *
 * <p>Implementations are pure functions — no Spring dependencies, fully testable
 * without a Spring context.
 */
public interface ProviderSelectionStrategy {

    /**
     * Returns providers ordered from most-preferred to least-preferred for
     * the given call tier. Providers not in {@code available} must be excluded.
     *
     * @param available all currently registered providers
     * @param tier      the call quality tier requested
     * @return ordered, non-null, possibly-empty list
     */
    List<RegisteredProvider> order(List<RegisteredProvider> available, ProviderTier tier);
}
