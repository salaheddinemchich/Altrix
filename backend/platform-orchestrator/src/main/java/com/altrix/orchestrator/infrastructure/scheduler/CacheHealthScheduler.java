package com.altrix.orchestrator.infrastructure.scheduler;

import com.altrix.orchestrator.infrastructure.cache.CacheHitRateBelowThresholdEvent;
import com.altrix.orchestrator.infrastructure.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Scheduled cache health monitor (#157).
 *
 * <p>Every {@code cache.health.check-interval-ms} (default 5 min) it reads
 * Redis {@code INFO stats} to compute the keyspace hit rate, logs totals, and
 * publishes a {@link CacheHitRateBelowThresholdEvent} when the rate drops below
 * the configured threshold. An {@link EventListener} on the same bean sends an
 * alert email when {@code cache.health.alert-enabled=true} and an ops address
 * is configured.
 *
 * <p>Redis counters are cumulative since server start. Hit-rate is meaningful
 * only after {@code cache.health.min-samples} total operations have occurred;
 * before that the check is skipped to avoid false alarms at startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheHealthScheduler {

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final CacheConfig cacheConfig;
    private final JavaMailSender mailSender;

    @Scheduled(fixedDelayString = "${cache.health.check-interval-ms:300000}")
    public void checkCacheHealth() {
        Properties stats = redisTemplate.execute(
                (RedisCallback<Properties>) conn -> conn.serverCommands().info("stats"));

        if (stats == null) {
            log.warn("[CacheHealth] could not retrieve Redis INFO stats");
            return;
        }

        long hits = parseLong(stats, "keyspace_hits");
        long misses = parseLong(stats, "keyspace_misses");
        long evictions = parseLong(stats, "evicted_keys");
        Long totalKeys = redisTemplate.execute(
                (RedisCallback<Long>) conn -> conn.serverCommands().dbSize());

        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total : 0.0;

        log.info("[CacheHealth] keys={} hits={} misses={} evictions={} hit-rate={}%",
                totalKeys != null ? totalKeys : "?",
                hits, misses, evictions,
                String.format("%.1f", hitRate * 100));

        CacheConfig.Health h = cacheConfig.health();
        if (total >= h.minSamples() && hitRate < h.minHitRate()) {
            eventPublisher.publishEvent(
                    CacheHitRateBelowThresholdEvent.of(hitRate, hits, misses, evictions));
        }
    }

    @EventListener
    public void onHitRateBelowThreshold(CacheHitRateBelowThresholdEvent event) {
        CacheConfig.Health h = cacheConfig.health();
        double pct = event.hitRate() * 100;
        double threshold = h.minHitRate() * 100;

        log.warn("[CacheHealth] ALERT — hit rate {:.1f}% is below threshold {:.0f}% " +
                        "(hits={} misses={} evictions={})",
                pct, threshold, event.hits(), event.misses(), event.evictions());

        if (!h.alertEnabled() || h.opsEmail().isBlank()) return;

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(h.opsEmail());
            msg.setSubject(String.format("[Altrix] Cache hit rate alert: %.1f%%", pct));
            msg.setText("""
                    Altrix cache health alert.

                    Hit rate  : %.1f%% (threshold: %.0f%%)
                    Hits      : %d
                    Misses    : %d
                    Evictions : %d
                    Detected  : %s

                    Review your cache TTL and warming strategy, or increase Redis memory.

                    — Altrix Platform Monitor
                    """.formatted(pct, threshold,
                    event.hits(), event.misses(), event.evictions(), event.detectedAt()));
            mailSender.send(msg);
            log.info("[CacheHealth] alert email sent to {}", h.opsEmail());
        } catch (Exception e) {
            log.warn("[CacheHealth] failed to send alert email: {}", e.getMessage());
        }
    }

    private static long parseLong(Properties props, String key) {
        String val = props.getProperty(key, "0");
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
