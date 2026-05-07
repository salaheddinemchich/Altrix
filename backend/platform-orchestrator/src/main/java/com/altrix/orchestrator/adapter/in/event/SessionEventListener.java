package com.altrix.orchestrator.adapter.in.event;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.event.*;
import com.altrix.orchestrator.domain.port.out.ApprovalNotificationPort;
import com.altrix.orchestrator.domain.port.out.SessionPauseHistoryPort;
import com.altrix.orchestrator.domain.port.out.SessionProgressPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Reacts to domain events emitted by {@link com.altrix.orchestrator.domain.model.session.WorkflowSession}.
 *
 * <p>All listeners fire on {@code AFTER_COMMIT} (#56), so no event is dispatched
 * if the originating transaction rolls back.
 *
 * <p>Each handler both logs, pushes a real-time update via {@link SessionProgressPort} (#60),
 * and (for pause/resume events) persists a history entry via {@link SessionPauseHistoryPort} (#72).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventListener {

    private final SessionProgressPort sessionProgressPort;
    private final SessionPauseHistoryPort pauseHistoryPort;
    private final ApprovalNotificationPort approvalNotificationPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SessionStarted event) {
        log.info("Session started — id={} job={} project={}",
                event.sessionId(), event.jobId(), event.projectId());
        sessionProgressPort.publishSessionUpdate(
                event.sessionId(), event.jobId(), SessionStatus.CONTEXT_ANALYSED, null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PlanReady event) {
        log.info("Plan ready — id={} job={}", event.sessionId(), event.jobId());
        sessionProgressPort.publishSessionUpdate(
                event.sessionId(), event.jobId(), SessionStatus.PLAN_READY, null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ApprovalRequested event) {
        log.info("Approval requested — id={} job={}", event.sessionId(), event.jobId());
        sessionProgressPort.publishSessionUpdate(
                event.sessionId(), event.jobId(), SessionStatus.AWAITING_APPROVAL, null);
        approvalNotificationPort.notifyApprovalRequired(event.sessionId(), event.jobId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(MigrationCompleted event) {
        log.info("Migration completed — id={} job={} files={}",
                event.sessionId(), event.jobId(), event.fileCount());
        sessionProgressPort.publishSessionUpdate(
                event.sessionId(), event.jobId(), SessionStatus.VALIDATING,
                event.fileCount() + " file(s) migrated");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SessionFailed event) {
        log.warn("Session failed — id={} job={} reason={}",
                event.sessionId(), event.jobId(), event.reason());
        sessionProgressPort.publishSessionUpdate(
                event.sessionId(), event.jobId(), SessionStatus.FAILED, event.reason());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SessionPaused event) {
        log.info("Session paused — id={} job={} pausedFrom={}",
                event.sessionId(), event.jobId(), event.pausedFrom());
        sessionProgressPort.publishSessionUpdate(
                event.sessionId(), event.jobId(), SessionStatus.PAUSED,
                "Paused from: " + event.pausedFrom());
        pauseHistoryPort.record(event.sessionId(), event.pausedFrom(), Instant.now());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SessionResumed event) {
        log.info("Session resumed — id={} job={} resumedTo={}",
                event.sessionId(), event.jobId(), event.resumedTo());
        sessionProgressPort.publishSessionUpdate(
                event.sessionId(), event.jobId(), event.resumedTo(), null);
        pauseHistoryPort.markResumed(event.sessionId(), Instant.now());
    }
}
