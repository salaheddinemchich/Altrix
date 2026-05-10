package com.altrix.orchestrator.adapter.out.cache;

import com.altrix.orchestrator.domain.port.out.TokenBlacklistPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed JWT blacklist.
 *
 * <p>Key format: {@code auth:blacklist:<jti>}
 * Value: "1" (placeholder — existence is the signal).
 * TTL: token's remaining lifetime, so entries self-expire and memory is bounded.
 *
 * <p>Failure mode: if Redis is unavailable, {@link #isBlacklisted} returns {@code false}
 * (fail-open) — a short Redis outage should not block all API requests. For high-security
 * environments, flip to fail-closed (throw or return true) and accept degraded availability.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTokenBlacklistAdapter implements TokenBlacklistPort {

    private static final String PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redis;

    @Override
    public void blacklist(String jti, long ttlSeconds) {
        if (ttlSeconds <= 0) return; // already expired — no need to store
        try {
            redis.opsForValue().set(PREFIX + jti, "1", ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to blacklist token jti={}: {}", jti, e.getMessage());
        }
    }

    @Override
    public boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
        } catch (Exception e) {
            log.error("Blacklist check failed for jti={} — fail-open: {}", jti, e.getMessage());
            return false; // fail-open: prefer availability over perfect revocation on Redis outage
        }
    }
}
