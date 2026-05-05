package com.altrix.orchestrator.adapter.out.cache;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.orchestrator.domain.port.out.MigrationPlanCachePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed cache for successful migration plans (#148).
 * Key format: {@code migration-plan:{projectId}:{targetStack}}
 * TTL: 7 days (plans remain useful across provider outages).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMigrationPlanCacheAdapter implements MigrationPlanCachePort {

    private static final String KEY_PREFIX = "migration-plan:";
    private static final Duration TTL      = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    @Override
    public void store(String projectId, String targetStack, List<MigratedFile> files) {
        String key = cacheKey(projectId, targetStack);
        try {
            String json = objectMapper.writeValueAsString(files);
            redisTemplate.opsForValue().set(key, json, TTL);
            log.debug("Cached {} migrated file(s) for project={} stack={}", files.size(), projectId, targetStack);
        } catch (Exception e) {
            // Best-effort — never fail a successful migration because of cache write
            log.warn("Failed to cache migration plan for project={}: {}", projectId, e.getMessage());
        }
    }

    @Override
    public Optional<List<MigratedFile>> loadLatest(String projectId, String targetStack) {
        String key = cacheKey(projectId, targetStack);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            List<MigratedFile> files = objectMapper.readValue(json, new TypeReference<>() {});
            log.info("Cache HIT — returning {} file(s) for project={} stack={}", files.size(), projectId, targetStack);
            return Optional.of(files);
        } catch (Exception e) {
            log.warn("Failed to load cached plan for project={}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String cacheKey(String projectId, String targetStack) {
        return KEY_PREFIX + projectId + ":" + targetStack;
    }
}
