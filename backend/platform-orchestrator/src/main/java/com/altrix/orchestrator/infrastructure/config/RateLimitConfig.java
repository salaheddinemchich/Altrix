package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-endpoint rate limit configuration.
 *
 * <p>Limits are expressed as {@code maxRequests} per {@code windowSeconds}.
 * A stricter limit is applied to auth endpoints (login, refresh, OAuth2 callback)
 * than to general API endpoints to slow credential-stuffing attacks.
 *
 * <p>All limits are enforced per client IP. The OAuth2 callback is also limited
 * per IP because it is the entry point for login abuse.
 */
@ConfigurationProperties(prefix = "security.rate-limit")
public record RateLimitConfig(
        int authMaxRequests,
        int authWindowSeconds,
        int apiMaxRequests,
        int apiWindowSeconds
) {
    public RateLimitConfig {
        if (authMaxRequests  <= 0) authMaxRequests  = 10;
        if (authWindowSeconds <= 0) authWindowSeconds = 60;
        if (apiMaxRequests   <= 0) apiMaxRequests   = 200;
        if (apiWindowSeconds <= 0) apiWindowSeconds  = 60;
    }
}
