package com.altrix.orchestrator.adapter.out.cache;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.orchestrator.domain.port.out.ContextAnalysisCachePort;
import com.altrix.orchestrator.infrastructure.config.CacheConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed implementation of {@link ContextAnalysisCachePort} (#154).
 *
 * <p>Key format: {@code ctx-analysis:{sha256hex}}
 * TTL: configurable via {@code cache.ttl.context-analysis} (default 1 h) — #157.
 *
 * <p>All operations are best-effort: a Redis failure never propagates to the
 * calling agent; instead it falls through to the AI call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisContextAnalysisCacheAdapter implements ContextAnalysisCachePort {

    private static final String KEY_PREFIX = "ctx-analysis:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheConfig cacheConfig;

    @Override
    public Optional<AnalysisReport> get(String contentHash) {
        String key = cacheKey(contentHash);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("[ContextAnalysisCache] MISS key={}", key);
                return Optional.empty();
            }
            AnalysisReport report = objectMapper.readValue(json, AnalysisReport.class);
            Long ttlSeconds = redisTemplate.getExpire(key);
            Duration ttl = cacheConfig.ttl().contextAnalysis();
            long ageSeconds = ttl.getSeconds() - (ttlSeconds != null ? ttlSeconds : 0);
            log.debug("[ContextAnalysisCache] HIT key={} age=~{}s", key, ageSeconds);
            return Optional.of(report);
        } catch (Exception e) {
            log.warn("[ContextAnalysisCache] read error key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String contentHash, AnalysisReport report) {
        String key = cacheKey(contentHash);
        try {
            String json = objectMapper.writeValueAsString(report);
            Duration ttl = cacheConfig.ttl().contextAnalysis();
            redisTemplate.opsForValue().set(key, json, ttl);
            log.debug("[ContextAnalysisCache] stored key={} ttl={}s ({} component(s), {} integration(s))",
                    key, ttl.getSeconds(),
                    report.detectedComponents().size(), report.detectedIntegrations().size());
        } catch (Exception e) {
            log.warn("[ContextAnalysisCache] write error key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public void evict(String contentHash) {
        String key = cacheKey(contentHash);
        try {
            redisTemplate.delete(key);
            log.debug("[ContextAnalysisCache] evicted key={}", key);
        } catch (Exception e) {
            log.warn("[ContextAnalysisCache] evict error key={}: {}", key, e.getMessage());
        }
    }

    private static String cacheKey(String contentHash) {
        return KEY_PREFIX + contentHash;
    }
}
