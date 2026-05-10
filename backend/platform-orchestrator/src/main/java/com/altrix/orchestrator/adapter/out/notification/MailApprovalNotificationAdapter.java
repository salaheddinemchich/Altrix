package com.altrix.orchestrator.adapter.out.notification;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.ApprovalNotificationPort;
import com.altrix.orchestrator.infrastructure.config.ApprovalNotificationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends a plain-text email to the configured reviewer address when a migration
 * plan is waiting for approval (#66).
 *
 * <p>When {@code approval.notification.enabled=false} or the reviewer address is blank,
 * the call is a no-op so the pipeline never fails due to mail misconfiguration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailApprovalNotificationAdapter implements ApprovalNotificationPort {

    private final JavaMailSender mailSender;
    private final ApprovalNotificationConfig config;

    @Override
    public void notifyApprovalRequired(WorkflowSessionId sessionId, String jobId) {
        if (!config.enabled() || config.reviewerEmail().isBlank()) {
            log.debug("Approval notification skipped (enabled={}, reviewer='{}')",
                    config.enabled(), config.reviewerEmail());
            return;
        }

        String approvalUrl = config.baseUrl() + "/sessions/" + sessionId.value() + "/approval";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(config.fromEmail());
        message.setTo(config.reviewerEmail());
        message.setSubject("Action required: migration plan awaiting approval (job " + jobId + ")");
        message.setText("""
                A migration plan is waiting for your approval.
                
                Job ID   : %s
                Session  : %s
                
                Review and approve or reject at:
                %s
                
                — Altrix Migration Platform
                """.formatted(jobId, sessionId.value(), approvalUrl));

        try {
            mailSender.send(message);
            log.info("Approval notification sent to '{}' for session '{}'",
                    config.reviewerEmail(), sessionId);
        } catch (MailException e) {
            log.warn("Failed to send approval notification for session '{}': {}",
                    sessionId, e.getMessage());
        }
    }
}
