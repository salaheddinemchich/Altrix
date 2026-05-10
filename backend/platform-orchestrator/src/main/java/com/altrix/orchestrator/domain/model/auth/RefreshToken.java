package com.altrix.orchestrator.domain.model.auth;

import java.time.Instant;

/**
 * Immutable value object representing a persisted refresh token.
 *
 * <p>Only the SHA-256 hex digest of the raw token is stored — never the token itself.
 * This prevents DB-level token theft: an attacker reading the table cannot use
 * the hashes directly.
 *
 * <p>On rotation, {@code replacedByHash} links to the successor token, enabling
 * detection of refresh token reuse (a previous token in the chain being replayed
 * indicates theft and triggers family revocation).
 */
public record RefreshToken(
        Long id,
        String tokenHash,
        String githubId,
        Instant expiresAt,
        Instant issuedAt,
        boolean revoked,
        String replacedByHash
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return !revoked && !isExpired();
    }
}
