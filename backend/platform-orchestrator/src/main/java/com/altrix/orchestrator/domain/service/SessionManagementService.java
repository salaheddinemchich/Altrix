package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.exception.SessionNotFoundException;
import com.altrix.orchestrator.domain.model.session.DecisionKind;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.EditPlanUseCase;
import com.altrix.orchestrator.domain.port.in.HandleApprovalUseCase;
import com.altrix.orchestrator.domain.port.in.PauseResumeSessionUseCase;
import com.altrix.orchestrator.domain.port.in.ResumeMigrationUseCase;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Domain service that handles session lifecycle operations driven by external actors:
 * plan approval/rejection (#64 #65), pause/resume (#69 #70), and batch timeout
 * rejection called by the scheduler (#68).
 *
 * <p>Each method is a simple load → mutate → save. State-transition guards are
 * enforced inside the aggregate; this service only decides WHICH transition to apply.
 */
@Slf4j
@RequiredArgsConstructor
public class SessionManagementService
        implements HandleApprovalUseCase, PauseResumeSessionUseCase, EditPlanUseCase {

    private final WorkflowSessionRepository sessionRepository;
    /** Triggered after the approve transition succeeds — runs the rest of the pipeline (#10). */
    private final ResumeMigrationUseCase resumeMigration;
    /** Hands off the resume work so the REST call returns quickly. */
    private final Executor resumeExecutor;
    /** Flips the job-side cache to MIGRATING immediately on approval. */
    private final JobStatusUpdatePort jobStatusUpdatePort;
    /** Emits a synthetic progress event so the timeline reacts the moment approval lands. */
    private final ProgressNotifierPort progressNotifier;

    // ── HandleApprovalUseCase ─────────────────────────────────────────────────

    @Override
    public WorkflowSession approve(WorkflowSessionId sessionId, String decidedBy) {
        WorkflowSession session = load(sessionId);
        session.startMigration();
        session.recordDecision(decidedBy, DecisionKind.APPROVED);
        WorkflowSession saved = sessionRepository.save(session);
        log.info("Plan approved by '{}' — session '{}' → MIGRATING", decidedBy, sessionId);

        // Align the job-side cache + push a synthetic progress event so the
        // JobDetail timeline flips from PLAN_READY to MIGRATING the moment
        // the user clicks Approve, without waiting for the migrator to start
        // emitting its own events (which can be 30+ s on a slow provider).
        jobStatusUpdatePort.markMigrating(saved.jobId());
        progressNotifier.notify(saved.jobId(), "Core Migrator", "RUNNING",
                "Migration approved — starting rewrite…");

        // Kick off migrator → validator → reporter asynchronously so the HTTP
        // call returns immediately; progress streams over the existing STOMP
        // topic /topic/jobs/{jobId}.
        resumeExecutor.execute(() -> {
            try {
                resumeMigration.resume(sessionId);
            } catch (RuntimeException e) {
                // ResumeMigrationService already marks the session FAILED and
                // notifies the client on its own; catch here only so the
                // executor's uncaught-exception handler does not spam the log.
                log.error("Resume task threw for session '{}': {}", sessionId, e.getMessage(), e);
            }
        });

        return saved;
    }

    @Override
    public WorkflowSession reject(WorkflowSessionId sessionId, String reason, String decidedBy) {
        WorkflowSession session = load(sessionId);
        session.fail(reason);
        session.recordDecision(decidedBy, DecisionKind.REJECTED);
        WorkflowSession saved = sessionRepository.save(session);
        log.info("Plan rejected by '{}' — session '{}' → FAILED (reason: {})", decidedBy, sessionId, reason);
        return saved;
    }

    // ── EditPlanUseCase (#10 follow-up) ───────────────────────────────────────

    @Override
    public WorkflowSession editPlan(WorkflowSessionId sessionId, MigrationPlan editedPlan) {
        WorkflowSession session = load(sessionId);
        if (session.plan() == null) {
            throw new IllegalStateException(
                    "Cannot edit plan for session " + sessionId + " — no AI plan stored yet");
        }
        // Preserve internal fields the reviewer should never override (storageKey,
        // projectId) by merging onto the existing plan.  This keeps the
        // downstream migrator's MinIO reference intact.
        MigrationPlan merged = new MigrationPlan(
                session.plan().projectId(),
                session.plan().storageKey(),
                editedPlan.targetStack(),
                editedPlan.steps(),
                editedPlan.riskLevel(),
                editedPlan.estimatedEffort(),
                editedPlan.summary(),
                editedPlan.targetFiles()
        );
        session.updatePlan(merged);
        WorkflowSession saved = sessionRepository.save(session);
        log.info("Plan edited by reviewer — session '{}' ({} steps, {} target files)",
                sessionId, merged.steps().size(), merged.targetFiles().size());
        return saved;
    }

    // ── PauseResumeSessionUseCase ─────────────────────────────────────────────

    @Override
    public WorkflowSession pause(WorkflowSessionId sessionId) {
        WorkflowSession session = load(sessionId);
        session.pause();
        WorkflowSession saved = sessionRepository.save(session);
        log.info("Session paused — '{}' (was: {})", sessionId, saved.pausedFrom());
        return saved;
    }

    @Override
    public WorkflowSession resume(WorkflowSessionId sessionId) {
        WorkflowSession session = load(sessionId);
        session.resume();
        WorkflowSession saved = sessionRepository.save(session);
        log.info("Session resumed — '{}' → {}", sessionId, saved.status());
        return saved;
    }

    // ── Approval timeout batch (called by ApprovalTimeoutScheduler) ───────────

    /**
     * Finds all sessions stuck in {@code AWAITING_APPROVAL} since before the cutoff
     * and auto-rejects them with a timeout message (#68).
     *
     * @param cutoff sessions whose {@code updatedAt} is before this instant are expired
     * @return number of sessions expired
     */
    public int expireStaleApprovals(Instant cutoff) {
        List<WorkflowSession> stale = sessionRepository.findByStatusAndUpdatedAtBefore(
                SessionStatus.AWAITING_APPROVAL, cutoff);

        int count = 0;
        for (WorkflowSession session : stale) {
            try {
                session.fail("Approval timeout — no response within the configured window");
                session.recordDecision("system:approval-timeout", DecisionKind.REJECTED);
                sessionRepository.save(session);
                count++;
                log.warn("Auto-rejected stale approval — session '{}'", session.id());
            } catch (Exception e) {
                log.error("Failed to auto-reject session '{}': {}", session.id(), e.getMessage());
            }
        }
        return count;
    }

    // ── private ───────────────────────────────────────────────────────────────

    private WorkflowSession load(WorkflowSessionId id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new SessionNotFoundException(id));
    }
}
