package com.altrix.orchestrator.adapter.out.cache;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.port.out.PlanSimilarityCachePort;
import com.altrix.orchestrator.infrastructure.config.CacheConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed implementation of {@link PlanSimilarityCachePort} (#155).
 *
 * <p>Key format: {@code plan-sim:{uuid}}
 * TTL: inherits {@code cache.ttl.migration-plan} (default 24 h).
 *
 * <p>Each entry is a JSON document: dep-signature list + Spring Boot major + plan.
 * {@link #loadAll()} uses non-blocking {@code SCAN} with a {@code plan-sim:*} pattern.
 * All operations are best-effort — exceptions are caught so Redis failures never
 * block the migration pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPlanSimilarityCacheAdapter implements PlanSimilarityCachePort {

    private static final String KEY_PREFIX = "plan-sim:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheConfig cacheConfig;

    @Override
    public void store(Set<String> depSignature, String springBootMajor, MigrationPlan plan) {
        String key = KEY_PREFIX + UUID.randomUUID();
        try {
            PlanSimilarityEntry entry = new PlanSimilarityEntry(
                    List.copyOf(depSignature), springBootMajor, plan);
            Duration ttl = cacheConfig.ttl().migrationPlan();
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), ttl);
            log.debug("[PlanSimilarityCache] stored key={} deps={} sbMajor={}",
                    key, depSignature.size(), springBootMajor);
        } catch (Exception e) {
            log.warn("[PlanSimilarityCache] write error key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public List<PlanSimilarityEntry> loadAll() {
        List<String> keys = scanKeys();
        if (keys.isEmpty()) return List.of();

        List<PlanSimilarityEntry> result = new ArrayList<>();
        for (String key : keys) {
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json == null) continue;
                result.add(objectMapper.readValue(json, new TypeReference<>() {}));
            } catch (Exception e) {
                log.warn("[PlanSimilarityCache] read error key={}: {}", key, e.getMessage());
            }
        }
        log.debug("[PlanSimilarityCache] loadAll returned {} entr(ies)", result.size());
        return result;
    }

    private List<String> scanKeys() {
        List<String> keys = new ArrayList<>();
        ScanOptions opts = ScanOptions.scanOptions()
                .match(KEY_PREFIX + "*")
                .count(200)
                .build();
        try {
            redisTemplate.execute((RedisCallback<Void>) conn -> {
                Cursor<byte[]> cursor = conn.keyCommands().scan(opts);
                try {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                } finally {
                    try {
                        cursor.close();
                    } catch (Exception closeEx) {
                        log.trace("[PlanSimilarityCache] cursor close error: {}", closeEx.getMessage());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[PlanSimilarityCache] SCAN error: {}", e.getMessage());
        }
        return keys;
    }
}
