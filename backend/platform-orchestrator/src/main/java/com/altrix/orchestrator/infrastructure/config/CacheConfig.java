package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cache TTL and health-monitoring configuration (#157).
 *
 * <pre>
 * cache:
 *   ttl:
 *     context-analysis: 1h
 *     migration-plan: 24h
 *   health:
 *     check-interval-ms: 300000
 *     min-hit-rate: 0.20
 *     min-samples: 10
 *     alert-enabled: true
 *     ops-email: ops@example.com
 * </pre>
 */
@ConfigurationProperties(prefix = "cache")
public record CacheConfig(Ttl ttl, Health health) {

    public CacheConfig {
        if (ttl == null) ttl = new Ttl(Duration.ofHours(1), Duration.ofHours(24));
        if (health == null) health = new Health(300_000L, 0.20, 10, false, "");
    }

    public record Ttl(Duration contextAnalysis, Duration migrationPlan) {
        public Ttl {
            if (contextAnalysis == null) contextAnalysis = Duration.ofHours(1);
            if (migrationPlan == null) migrationPlan = Duration.ofHours(24);
        }
    }

    public record Health(
            long checkIntervalMs,
            double minHitRate,
            int minSamples,
            boolean alertEnabled,
            String opsEmail
    ) {
        public Health {
            if (opsEmail == null) opsEmail = "";
        }
    }
}
