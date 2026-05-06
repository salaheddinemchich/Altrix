package com.altrix.orchestrator.infrastructure.scheduler;

import com.altrix.orchestrator.domain.service.SessionManagementService;
import com.altrix.orchestrator.infrastructure.config.ApprovalConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Scheduled task that auto-rejects sessions stuck in {@code AWAITING_APPROVAL}
 * beyond the configured timeout (#68).
 *
 * <p>Runs on the cron expression from {@link ApprovalConfig#schedulerCron()}.
 * Default: every minute. Each invocation is cheap — it queries only sessions
 * in AWAITING_APPROVAL with {@code updated_at} older than the cutoff, which
 * is covered by {@code idx_ws_status_updated} (V7 migration).
 *
 * <p>Uses {@code @Scheduled(cron)} bound to the config property so it can be
 * tuned per-environment without a code change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTimeoutScheduler {

    private final SessionManagementService sessionManagementService;
    private final ApprovalConfig           approvalConfig;

    @Scheduled(cron = "${approval.scheduler-cron:0 * * * * *}")
    public void expireStaleApprovals() {
        Instant cutoff = Instant.now().minus(approvalConfig.timeout());
        int expired = sessionManagementService.expireStaleApprovals(cutoff);
        if (expired > 0) {
            log.info("Approval timeout sweep: expired {} stale session(s) (cutoff={})",
                    expired, cutoff);
        }
    }
}
