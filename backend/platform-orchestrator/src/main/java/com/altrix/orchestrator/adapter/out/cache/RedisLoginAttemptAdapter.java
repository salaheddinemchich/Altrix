package com.altrix.orchestrator.adapter.out.cache;

import com.altrix.orchestrator.domain.port.out.LoginAttemptPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed brute-force protection using an exponential-backoff lockout.
 *
 * <p>Key format:
 * <ul>
 *   <li>{@code auth:attempts:<key>} — failure counter (sliding window)
 *   <li>{@code auth:locked:<key>}   — lock flag (set on threshold breach)
 * </ul>
 *
 * <p>Lockout duration doubles on each consecutive lock, capped at 1 hour:
 * 1 min → 2 min → 4 min → … → 60 min.
 *
 * <p>{@code key} is typically the client IP address; for authenticated endpoints
 * it can be the githubId to track per-user abuse regardless of IP.
 */
@Slf4j
@Component
public class RedisLoginAttemptAdapter implements LoginAttemptPort {

    private static final String ATTEMPTS_PREFIX = "auth:attempts:";
    private static final String LOCKED_PREFIX   = "auth:locked:";
    private static final String LOCK_COUNT_PREFIX = "auth:lockcount:";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final long windowSeconds;

    public RedisLoginAttemptAdapter(
            StringRedisTemplate redis,
            @Value("${security.brute-force.max-attempts:5}") int maxAttempts,
            @Value("${security.brute-force.window-seconds:300}") long windowSeconds) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
    }

    @Override
    public void recordFailure(String key) {
        try {
            String attemptsKey = ATTEMPTS_PREFIX + key;
            Long count = redis.opsForValue().increment(attemptsKey);
            redis.expire(attemptsKey, windowSeconds, TimeUnit.SECONDS);

            if (count != null && count >= maxAttempts) {
                long lockSeconds = computeLockSeconds(key);
                redis.opsForValue().set(LOCKED_PREFIX + key, "1", lockSeconds, TimeUnit.SECONDS);
                redis.opsForValue().increment(LOCK_COUNT_PREFIX + key);
                redis.expire(LOCK_COUNT_PREFIX + key, 86_400L, TimeUnit.SECONDS); // 24 h
                log.warn("Key {} locked for {} seconds after {} failures", key, lockSeconds, count);
            }
        } catch (Exception e) {
            log.error("recordFailure failed for key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public void recordSuccess(String key) {
        try {
            redis.delete(ATTEMPTS_PREFIX + key);
            redis.delete(LOCKED_PREFIX + key);
        } catch (Exception e) {
            log.error("recordSuccess failed for key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public boolean isBlocked(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(LOCKED_PREFIX + key));
        } catch (Exception e) {
            log.error("isBlocked check failed for key={} — fail-open: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public int failureCount(String key) {
        try {
            String val = redis.opsForValue().get(ATTEMPTS_PREFIX + key);
            return val != null ? Integer.parseInt(val) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Exponential backoff: 60s * 2^(lockCount-1), capped at 3600s. */
    private long computeLockSeconds(String key) {
        try {
            String countStr = redis.opsForValue().get(LOCK_COUNT_PREFIX + key);
            int lockCount = countStr != null ? Integer.parseInt(countStr) : 0;
            long seconds = 60L * (1L << Math.min(lockCount, 5)); // max 2^5 = 32 → 1920s
            return Math.min(seconds, 3_600L);
        } catch (Exception e) {
            return 60L;
        }
    }
}
