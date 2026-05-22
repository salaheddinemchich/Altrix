package com.altrix.orchestrator.domain.model.session;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.exception.IllegalStateTransitionException;
import com.altrix.orchestrator.domain.model.session.event.*;

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
    private final String jobId;
    private final String projectId;
    private SessionStatus status;
    private MigrationPlan plan;
    private String errorMessage;
    private SessionStatus pausedFrom;
    private int consecutiveAgentErrors;
    private List<MigratedFile> migratedFiles;
    private final Instant createdAt;
    private Instant updatedAt;
    /** Optimistic lock version — 0 for new sessions; threaded through from the JPA entity. */
    private final long version;

    /** Approval audit — who answered the AWAITING_APPROVAL gate, when, and how. #125 / #126 */
    private String decidedBy;
    private Instant decidedAt;
    /** APPROVED / REJECTED — null until the gate is answered. */
    private DecisionKind decisionKind;

    /**
     * Domain events collected during this unit of work; drained by the repository.
     */
    private final List<Object> pendingEvents = new ArrayList<>();

    // ── Factory ───────────────────────────────────────────────────────────────

    public static WorkflowSession create(String jobId, String projectId) {
        return new WorkflowSession(
                WorkflowSessionId.generate(),
                jobId,
                projectId,
                SessionStatus.PENDING,
                null, null, null,
                0, List.of(),
                Instant.now(), 0L,
                null, null, null);
    }

    /**
     * Reconstitution constructor — back-compat overload without approval audit
     * fields.  Used by the few call-sites that don't (yet) carry them; the
     * persistence adapter uses the full one below.
     */
    public WorkflowSession(WorkflowSessionId id, String jobId, String projectId,
                           SessionStatus status, MigrationPlan plan,
                           String errorMessage, SessionStatus pausedFrom,
                           int consecutiveAgentErrors, List<MigratedFile> migratedFiles,
                           Instant createdAt, long version) {
        this(id, jobId, projectId, status, plan, errorMessage, pausedFrom,
                consecutiveAgentErrors, migratedFiles, createdAt, version,
                null, null, null);
    }

    /**
     * Reconstitution constructor used by the persistence adapter, including
     * the approval audit fields (#125 / #126).
     */
    public WorkflowSession(WorkflowSessionId id, String jobId, String projectId,
                           SessionStatus status, MigrationPlan plan,
                           String errorMessage, SessionStatus pausedFrom,
                           int consecutiveAgentErrors, List<MigratedFile> migratedFiles,
                           Instant createdAt, long version,
                           String decidedBy, Instant decidedAt, DecisionKind decisionKind) {
        this.id = Objects.requireNonNull(id);
        this.jobId = Objects.requireNonNull(jobId);
        this.projectId = Objects.requireNonNull(projectId);
        this.status = Objects.requireNonNull(status);
        this.plan = plan;
        this.errorMessage = errorMessage;
        this.pausedFrom = pausedFrom;
        this.consecutiveAgentErrors = consecutiveAgentErrors;
        this.migratedFiles = migratedFiles != null ? List.copyOf(migratedFiles) : List.of();
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = createdAt;
        this.version = version;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionKind = decisionKind;
    }

    /**
     * Records who answered the approval gate, when, and how.  Called from
     * {@code SessionManagementService.approve/reject} right after the state
     * transition so the audit row commits in the same transaction.
     */
    public void recordDecision(String decidedBy, DecisionKind kind) {
        this.decidedBy = decidedBy;
        this.decidedAt = Instant.now();
        this.decisionKind = Objects.requireNonNull(kind);
    }

    // ── State machine ─────────────────────────────────────────────────────────

    /**
     * Transitions to CONTEXT_ANALYSED. Valid from: PENDING.
     */
    public void beginContextAnalysis() {
        guard(SessionStatus.CONTEXT_ANALYSED);
        this.status = SessionStatus.CONTEXT_ANALYSED;
        this.updatedAt = Instant.now();
        pendingEvents.add(SessionStarted.of(id, jobId, projectId));
    }

    /**
     * Transitions to PLAN_READY and stores the produced plan. Valid from: CONTEXT_ANALYSED.
     */
    public void completePlan(MigrationPlan plan) {
        guard(SessionStatus.PLAN_READY);
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.status = SessionStatus.PLAN_READY;
        this.updatedAt = Instant.now();
        pendingEvents.add(PlanReady.of(id, jobId, plan));
    }

    /**
     * Transitions to AWAITING_APPROVAL. Valid from: PLAN_READY.
     */
    public void requestApproval() {
        guard(SessionStatus.AWAITING_APPROVAL);
        this.status = SessionStatus.AWAITING_APPROVAL;
        this.updatedAt = Instant.now();
        pendingEvents.add(ApprovalRequested.of(id, jobId));
    }

    /**
     * Transitions to MIGRATING. Valid from: PENDING (shortcut), PLAN_READY, or AWAITING_APPROVAL.
     */
    public void startMigration() {
        guard(SessionStatus.MIGRATING);
        this.status = SessionStatus.MIGRATING;
        this.updatedAt = Instant.now();
    }

    /**
     * Transitions to VALIDATING. Valid from: MIGRATING.
     */
    public void startValidation(int fileCount) {
        guard(SessionStatus.VALIDATING);
        this.status = SessionStatus.VALIDATING;
        this.updatedAt = Instant.now();
        pendingEvents.add(MigrationCompleted.of(id, jobId, fileCount));
    }

    /**
     * Transitions to DONE (terminal). Valid from: MIGRATING (shortcut) or VALIDATING.
     */
    public void complete() {
        guard(SessionStatus.DONE);
        this.status = SessionStatus.DONE;
        this.updatedAt = Instant.now();
    }

    /**
     * Transitions to FAILED (terminal) from any non-terminal state.
     */
    public void fail(String reason) {
        guard(SessionStatus.FAILED);
        this.errorMessage = reason;
        this.status = SessionStatus.FAILED;
        this.updatedAt = Instant.now();
        pendingEvents.add(SessionFailed.of(id, jobId, reason));
    }

    /**
     * Transitions to PAUSED; remembers the current state for resume.
     */
    public void pause() {
        guard(SessionStatus.PAUSED);
        this.pausedFrom = this.status;
        this.status = SessionStatus.PAUSED;
        this.updatedAt = Instant.now();
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
        this.status = pausedFrom;
        this.pausedFrom = null;
        this.updatedAt = Instant.now();
        pendingEvents.add(SessionResumed.of(id, jobId, this.status));
    }

    // ── Auto-pause circuit-breaker (#71) ─────────────────────────────────────

    /**
     * Records an agent failure and applies auto-pause when the consecutive error
     * count reaches {@code autoPauseThreshold}.
     *
     * @return {@code true} if the session was auto-paused; {@code false} if it was failed
     */
    public boolean handleAgentFailure(String reason, int autoPauseThreshold) {
        consecutiveAgentErrors++;
        if (consecutiveAgentErrors >= autoPauseThreshold) {
            pause();
            return true;
        }
        fail(reason);
        return false;
    }

    /**
     * Resets the consecutive error counter after a successful pipeline run.
     */
    public void resetAgentErrors() {
        consecutiveAgentErrors = 0;
    }

    /**
     * Stores the files produced by the migrator for later retrieval via the diff endpoint (#122).
     */
    public void storeMigratedFiles(List<MigratedFile> files) {
        this.migratedFiles = files != null ? List.copyOf(files) : List.of();
    }

    /**
     * Reviewer override of the AI-proposed plan (#10 follow-up).
     *
     * <p>Allowed only at the approval gate so an in-flight migration cannot
     * have its plan swapped underneath it.  Status is not changed by this
     * call — the reviewer still needs to Approve / Reject afterwards.  The
     * existing migrator path then picks up {@link #plan()} verbatim when the
     * resume kicks off.
     */
    public void updatePlan(MigrationPlan editedPlan) {
        Objects.requireNonNull(editedPlan, "editedPlan must not be null");
        if (status != SessionStatus.PLAN_READY && status != SessionStatus.AWAITING_APPROVAL) {
            throw new IllegalStateTransitionException(status, status);
        }
        this.plan = editedPlan;
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

    public WorkflowSessionId id() {
        return id;
    }

    public String jobId() {
        return jobId;
    }

    public String projectId() {
        return projectId;
    }

    public SessionStatus status() {
        return status;
    }

    public MigrationPlan plan() {
        return plan;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public SessionStatus pausedFrom() {
        return pausedFrom;
    }

    public int consecutiveAgentErrors() {
        return consecutiveAgentErrors;
    }

    public List<MigratedFile> migratedFiles() {
        return migratedFiles;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public String decidedBy() {
        return decidedBy;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public DecisionKind decisionKind() {
        return decisionKind;
    }

    // ── Guard ─────────────────────────────────────────────────────────────────

    private void guard(SessionStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException(status, next);
        }
    }
}
