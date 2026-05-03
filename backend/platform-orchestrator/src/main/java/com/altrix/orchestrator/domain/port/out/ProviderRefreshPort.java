package com.altrix.orchestrator.domain.port.out;

/**
 * Secondary port that triggers a live reload of the active AI providers.
 * Called by the application service after a config change so the new
 * settings take effect without a server restart.
 */
public interface ProviderRefreshPort {
    void refreshProviders();
}
