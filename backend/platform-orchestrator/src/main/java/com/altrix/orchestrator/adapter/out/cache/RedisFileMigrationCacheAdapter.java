package com.altrix.orchestrator.adapter.out.cache;

import com.altrix.orchestrator.domain.port.out.FileMigrationCachePort;
import com.altrix.orchestrator.infrastructure.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed implementation of {@link FileMigrationCachePort} (#28).
 *
 * <p>Stores per-file Agent 3 outputs keyed by
 * {@code SHA-256(systemPromptHash + path + contentHash)}.  TTL configurable
 * via {@code cache.ttl.file-migration} (default 30 min).
 *
 * <p>Best-effort semantics — a Redis outage falls through to a live AI call
 * rather than crashing the pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisFileMigrationCacheAdapter implements FileMigrationCachePort {

    private static final String KEY_PREFIX = "file-migration:";

    private final StringRedisTemplate redisTemplate;
    private final CacheConfig cacheConfig;

    @Override
    public Optional<String> get(String cacheKey) {
        String key = KEY_PREFIX + cacheKey;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                log.debug("[FileMigrationCache] MISS key={}", key);
                return Optional.empty();
            }
            log.debug("[FileMigrationCache] HIT  key={} ({} chars)", key, cached.length());
            return Optional.of(cached);
        } catch (Exception e) {
            log.warn("[FileMigrationCache] read error key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String cacheKey, String migratedContent) {
        if (migratedContent == null) return;
        String key = KEY_PREFIX + cacheKey;
        try {
            Duration ttl = cacheConfig.ttl().fileMigration();
            redisTemplate.opsForValue().set(key, migratedContent, ttl);
            log.debug("[FileMigrationCache] stored key={} ttl={}s ({} chars)",
                    key, ttl.getSeconds(), migratedContent.length());
        } catch (Exception e) {
            log.warn("[FileMigrationCache] write error key={}: {}", key, e.getMessage());
        }
    }
}
