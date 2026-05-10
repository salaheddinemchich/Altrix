package com.altrix.orchestrator.domain.port.out;

/**
 * Secondary port for the JWT access-token blacklist.
 *
 * <p>Only the {@code jti} (UUID) is stored, never the full token.
 * TTL is set to the token's remaining lifetime so entries self-expire,
 * keeping Redis memory bounded without a separate cleanup job.
 *
 * <p>Why Redis? O(1) GET on every authenticated request — a DB lookup would
 * add unacceptable latency to the hot path. Redis also provides atomic TTL-based
 * expiry without a maintenance job.
 */
public interface TokenBlacklistPort {

    /**
     * Adds a JTI to the blacklist with an explicit TTL.
     *
     * @param jti          JWT ID claim value
     * @param ttlSeconds   how long to keep the entry (should match token's remaining lifetime)
     */
    void blacklist(String jti, long ttlSeconds);

    boolean isBlacklisted(String jti);
}
