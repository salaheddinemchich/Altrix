package com.altrix.job.domain.model;

/**
 * Governs which AI provider tier a migration job may use (#52).
 *
 * <p>{@code DEFAULT} follows the org/plan setting; {@code ALL_LOCAL} forces
 * FREE-tier (local) providers regardless of plan — guaranteeing that sensitive
 * source code never leaves the on-premise network.
 */
public enum JobProviderProfile {
    DEFAULT,
    ALL_LOCAL
}
