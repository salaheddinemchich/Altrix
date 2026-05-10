package com.altrix.orchestrator.domain.port.out;

/**
 * Secondary port for brute-force / credential-stuffing protection.
 *
 * <p>Tracks failed login attempts per IP address (and optionally per username)
 * in Redis with a sliding window. When the threshold is exceeded the account
 * is temporarily locked and the IP throttled.
 *
 * <p>Exponential backoff: lockout duration doubles on each successive lock:
 * 1 min → 2 min → 4 min → … capped at 1 hour.
 */
public interface LoginAttemptPort {

    void recordFailure(String key);

    void recordSuccess(String key);

    boolean isBlocked(String key);

    int failureCount(String key);
}
