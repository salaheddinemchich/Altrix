package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the approval-gate timeout scheduler (#68).
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * approval:
 *   timeout: 30m
 *   scheduler-cron: "0 * * * * *"   # every minute
 * </pre>
 */
@ConfigurationProperties(prefix = "approval")
public record ApprovalConfig(

        /** How long a session may stay in AWAITING_APPROVAL before being auto-rejected. */
        Duration timeout,

        /** Spring cron expression for the timeout-check scheduler. */
        String schedulerCron
) {
    public ApprovalConfig {
        if (timeout == null)       timeout       = Duration.ofMinutes(30);
        if (schedulerCron == null) schedulerCron = "0 * * * * *";
    }
}
