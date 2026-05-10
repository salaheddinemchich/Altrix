package com.altrix.orchestrator.infra.ai.exception;

import com.altrix.orchestrator.infra.ai.provider.ProviderTier;

/**
 * Sanitized wrapper thrown when a provider call fails.
 *
 * <p>The original exception message is intentionally NOT propagated up the stack.
 * Provider HTTP error bodies can contain enough context to hint at an API key
 * (e.g. the last 4 chars in a 401 response). Log the cause at DEBUG; expose only
 * the provider ID and tier to callers.
 */
public final class ProviderCallException extends RuntimeException {

    private final String providerId;
    private final ProviderTier tier;

    public ProviderCallException(String providerId, ProviderTier tier, Throwable cause) {
        super("Provider [" + providerId + "] failed for tier " + tier, cause);
        this.providerId = providerId;
        this.tier = tier;
    }

    public String providerId() {
        return providerId;
    }

    public ProviderTier tier() {
        return tier;
    }
}
