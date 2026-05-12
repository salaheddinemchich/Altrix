package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.auth.RefreshToken;

import java.time.Instant;
import java.util.Optional;

/**
 * Secondary port for refresh token persistence.
 *
 * <p>All lookups are by {@code tokenHash} (SHA-256 hex) — the raw token never
 * crosses this boundary.
 */
public interface RefreshTokenRepository {

    RefreshToken save(String tokenHash, String userId, Instant expiresAt);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes the token and records what it was replaced by (rotation chain). */
    void revokeAndReplace(String tokenHash, String replacedByHash);

    /** Revokes all active tokens for a user (used on logout + suspicious activity). */
    void revokeAllForUser(String userId);

    /** Purges expired rows — called by a maintenance scheduler. */
    void deleteExpiredBefore(Instant cutoff);
}
