package com.migrator.job.adapter.out.cache;

import com.migrator.job.domain.port.out.JobCachePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Secondary adapter — implements {@link JobCachePort} using Redis.
 *
 * <p>Status entries expire after 24 hours — long enough to cover
 * any migration job lifecycle, short enough to avoid stale data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisJobCacheAdapter implements JobCachePort {

    private static final String  KEY_PREFIX = "job:status:";
    private static final Duration TTL       = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void putStatus(String jobId, String status) {
        String key = KEY_PREFIX + jobId;
        redisTemplate.opsForValue().set(key, status, TTL);
        log.debug("Cached status '{}' for job '{}'", status, jobId);
    }

    @Override
    public Optional<String> getStatus(String jobId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + jobId);
        return Optional.ofNullable(value);
    }

    @Override
    public void evict(String jobId) {
        redisTemplate.delete(KEY_PREFIX + jobId);
        log.debug("Evicted cache for job '{}'", jobId);
    }
}
