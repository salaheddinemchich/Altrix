package com.altrix.orchestrator.infrastructure.cache;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.port.out.PlanSimilarityCachePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads pre-built migration plan patterns into the Redis similarity cache on
 * application startup (#156).
 *
 * <p>Patterns are bundled in {@code src/main/resources/cache-warmup/patterns.json}.
 * Each pattern carries a {@code patternId}, {@code depSignature}, {@code springBootMajor},
 * and a serialised {@link MigrationPlan}.
 *
 * <p>Warming is idempotent: each pattern key ({@code plan-warmup:{patternId}}) is
 * checked before writing — if the key already exists the entry is skipped.  This
 * prevents redundant writes on every rolling restart.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmupListener {

    private static final String WARMUP_GUARD_PREFIX = "plan-warmup:";

    private final PlanSimilarityCachePort similarityCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationStartedEvent.class)
    public void warmUp() {
        ClassPathResource resource = new ClassPathResource("cache-warmup/patterns.json");
        if (!resource.exists()) {
            log.debug("[CacheWarmup] patterns.json not found — skipping");
            return;
        }

        JsonNode patterns;
        try (InputStream is = resource.getInputStream()) {
            patterns = objectMapper.readTree(is);
        } catch (Exception e) {
            log.warn("[CacheWarmup] failed to read patterns.json: {}", e.getMessage());
            return;
        }

        int[] counters = {0, 0}; // [loaded, skipped]
        for (JsonNode node : patterns) {
            processPattern(node, counters);
        }

        int loaded = counters[0];
        int skipped = counters[1];
        if (loaded > 0 || skipped > 0) {
            log.info("[CacheWarmup] complete — loaded={} skipped={}", loaded, skipped);
        }
    }

    private void processPattern(JsonNode node, int[] counters) {
        String patternId = node.path("patternId").asText("");
        if (patternId.isBlank()) return;

        String guardKey = WARMUP_GUARD_PREFIX + patternId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(guardKey))) {
            counters[1]++;
            return;
        }

        try {
            Set<String> depSig = parseDepSignature(node.path("depSignature"));
            String springBootMajor = node.path("springBootMajor").asText("");
            MigrationPlan plan = objectMapper.treeToValue(node.path("migrationPlan"), MigrationPlan.class);
            similarityCache.store(depSig, springBootMajor, plan);
            redisTemplate.opsForValue().set(guardKey, "1");
            counters[0]++;
        } catch (Exception e) {
            log.warn("[CacheWarmup] failed to load pattern '{}': {}", patternId, e.getMessage());
        }
    }

    private static Set<String> parseDepSignature(JsonNode node) {
        Set<String> sig = new HashSet<>();
        if (node.isArray()) {
            node.forEach(n -> { if (!n.asText("").isBlank()) sig.add(n.asText().toLowerCase()); });
        }
        return sig;
    }
}
