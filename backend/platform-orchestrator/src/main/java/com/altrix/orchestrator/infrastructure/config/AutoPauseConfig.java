package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls the auto-pause circuit-breaker for the agent pipeline (#71).
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * workflow:
 *   auto-pause:
 *     threshold: 3   # pause session after 3 consecutive agent failures
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.auto-pause")
public record AutoPauseConfig(int threshold) {
    public AutoPauseConfig {
        if (threshold <= 0) threshold = 3;
    }
}
