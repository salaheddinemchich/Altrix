package com.altrix.orchestrator.domain.model.session;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.exception.IllegalStateTransitionException;
import com.altrix.orchestrator.domain.model.session.event.ApprovalRequested;
import com.altrix.orchestrator.domain.model.session.event.MigrationCompleted;
import com.altrix.orchestrator.domain.model.session.event.PlanReady;
import com.altrix.orchestrator.domain.model.session.event.SessionFailed;
import com.altrix.orchestrator.domain.model.session.event.SessionPaused;
import com.altrix.orchestrator.domain.model.session.event.SessionStarted;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Central DDD aggregate root for a migration session (#54).
 *
 * <p>Owns the lifecycle state machine and collects domain events on every
 * transition. The repository adapter drains and publishes events after a
 * successful persist, guaranteeing no event fires on rollback.
 *
 * <p>Zero Spring / JPA / Jackson imports — all framework concerns live in
 * the adapter and infrastructure layers.
 */
public class WorkflowSession {

    private final WorkflowSessionId id;
    private final String            jobId;
    private final String            projectId;
    private       SessionStatus     status;
    private       MigrationPlan     plan;
    private       String            errorMessage;
    private       SessionStatus     pausedFrom;
    private final Instant           createdAt;
    private       Instant           updatedAt;

    /** Domain events collected during this unit of work; drained by the repository. */
    private final List<Object> pendingEvents = new ArrayList<>();

    // ── Factory ───────────────────────────────────────────────────────────────

    public static WorkflowSession create(String jobId, String projectId) {
        return new WorkflowSession(
                WorkflowSessionId.generate(),
                jobId,
                projectId,
                SessionStatus.PENDING,
                null, null, null,
                Instant.now());
    }

    /** Reconstitution constructor used by the persistence adapter. */
    public WorkflowSession(WorkflowSessionId id, String jobId, String projectId,
                           SessionStatus status, MigrationPlan plan,
                           String errorMessage, SessionStatus pausedFrom,
                           Instant createdAt) {
        this.id           = Objects.requireNonNull(id);
        this.jobId        = Objects.requireNonNull(jobId);
        this.projectId    = Objects.requireNonNull(projectId);
        this.status       = Objects.requireNonNull(status);
        this.plan         = plan;
        this.errorMessage = errorMessage;
        this.pausedFrom   = pausedFrom;
        this.createdAt    = Objects.requireNonNull(createdAt);
        this.updatedAt    = createdAt;
    }

    // ── State machine ─────────────────────────────────────────────────────────

    /**
     * Transitions to CONTEXT_ANALYSED, recording a {@link SessionStarted} event.
     * Valid from: PENDING.
     */
    public void beginContextAnalysis() {
        guard(SessionStatus.CONTEXT_ANALYSED);
        this.status = SessionStatus.CONTEXT_ANALYSED;
        this.updatedAt = Instant.now();
        pendingEvents.add(SessionStarted.of(id, jobId, projectId));
    }

    /**
     * Transitions to PLAN_READY and stores the produced plan.
     * Valid from: CONTEXT_ANALYSED.
     */
    public void completePlan(MigrationPlan plan) {
        guard(SessionStatus.PLAN_READY);
        this.plan      = Objects.requireNonNull(plan, "plan must not be null");
        this.status    = SessionStatus.PLAN_READY;
        this.updatedAt = Instant.now();
        pendingEvents.add(PlanReady.of(id, jobId, plan));
    }

    /**
     * Transitions to AWAITING_APPROVAL.
     * Valid from: PLAN_READY.
     */
    public void requestApproval() {
        guard(SessionStatus.AWAITING_APPROVAL);
        this.status    = SessionStatus.AWAITING_APPROVAL;
        this.updatedAt = Instant.now();
        pendingEvents.add(ApprovalRequested.of(id, jobId));
    }

    /**
     * Transitions to MIGRATING.
     * Valid from: PENDING (Sprint-1 shortcut), PLAN_READY, or AWAITING_APPROVAL.
     */
    public void startMigration() {
        guard(SessionStatus.MIGRATING);
        this.status    = SessionStatus.MIGRATING;
        this.updatedAt = Instant.now();
    }

    /**
     * Transitions to VALIDATING, recording a {@link MigrationCompleted} event.
     * Valid from: MIGRATING.
     */
    public void startValidation(int fileCount) {
        guard(SessionStatus.VALIDATING);
        this.status    = SessionStatus.VALIDATING;
        this.updatedAt = Instant.now();
        pendingEvents.add(MigrationCompleted.of(id, jobId, fileCount));
    }

    /**
     * Transitions to DONE (terminal).
     * Valid from: MIGRATING (Sprint-1 shortcut) or VALIDATING.
     */
    public void complete() {
        guard(SessionStatus.DONE);
        this.status    = SessionStatus.DONE;
        this.updatedAt = Instant.now();
    }

    /**
     * Transitions to FAILED (terminal) from any non-terminal state.
     */
    public void fail(String reason) {
        guard(SessionStatus.FAILED);
        this.errorMessage = reason;
        this.status       = SessionStatus.FAILED;
        this.updatedAt    = Instant.now();
        pendingEvents.add(SessionFailed.of(id, jobId, reason));
    }

    /**
     * Transitions to PAUSED from any non-terminal, non-paused state.
     * Remembers the current state so it can be resumed later (Sprint 2).
     */
    public void pause() {
        guard(SessionStatus.PAUSED);
        this.pausedFrom = this.status;
        this.status     = SessionStatus.PAUSED;
        this.updatedAt  = Instant.now();
        pendingEvents.add(SessionPaused.of(id, jobId, pausedFrom));
    }

    /**
     * Resumes from PAUSED back to the state that was active before pausing.
     */
    public void resume() {
        if (status != SessionStatus.PAUSED) {
            throw new IllegalStateTransitionException(status, SessionStatus.PAUSED);
        }
        if (pausedFrom == null) {
            throw new IllegalStateException("Cannot resume: no pausedFrom state recorded for session " + id);
        }
        this.status    = pausedFrom;
        this.pausedFrom = null;
        this.updatedAt = Instant.now();
    }

    // ── Event drain ───────────────────────────────────────────────────────────

    /**
     * Returns all collected domain events and clears the internal list.
     * Called exclusively by the repository adapter after a successful persist.
     */
    public List<Object> drainEvents() {
        List<Object> snapshot = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return snapshot;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public WorkflowSessionId id()           { return id; }
    public String            jobId()        { return jobId; }
    public String            projectId()    { return projectId; }
    public SessionStatus     status()       { return status; }
    public MigrationPlan     plan()         { return plan; }
    public String            errorMessage() { return errorMessage; }
    public SessionStatus     pausedFrom()   { return pausedFrom; }
    public Instant           createdAt()    { return createdAt; }
    public Instant           updatedAt()    { return updatedAt; }

    // ── Guard ─────────────────────────────────────────────────────────────────

    private void guard(SessionStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException(status, next);
        }
    }
}
