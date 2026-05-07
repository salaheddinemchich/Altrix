package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for approval-request notifications (#66).
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * approval:
 *   notification:
 *     enabled: true
 *     reviewer-email: reviewer@example.com
 *     from-email: altrix@example.com
 *     base-url: http://localhost:4200
 * </pre>
 *
 * <p>When {@code enabled} is false the mail adapter is a no-op.
 */
@ConfigurationProperties(prefix = "approval.notification")
public record ApprovalNotificationConfig(
        boolean enabled,
        String reviewerEmail,
        String fromEmail,
        String baseUrl
) {
    public ApprovalNotificationConfig {
        if (reviewerEmail == null) reviewerEmail = "";
        if (fromEmail == null) fromEmail = "altrix-noreply@localhost";
        if (baseUrl == null) baseUrl = "http://localhost:4200";
    }
}
