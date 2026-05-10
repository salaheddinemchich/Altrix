package com.altrix.orchestrator.domain.model.session;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.exception.IllegalStateTransitionException;
import com.altrix.orchestrator.domain.model.session.event.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowSessionTest {

    private static WorkflowSession pending() {
        return WorkflowSession.create("job-1", "proj-1");
    }

    // ── factory / create ─────────────────────────────────────────────────────

    @Test
    void create_initializesSessionInPendingState() {
        WorkflowSession s = pending();
        assertThat(s.status()).isEqualTo(SessionStatus.PENDING);
        assertThat(s.jobId()).isEqualTo("job-1");
        assertThat(s.projectId()).isEqualTo("proj-1");
        assertThat(s.id()).isNotNull();
        assertThat(s.createdAt()).isNotNull();
        assertThat(s.plan()).isNull();
        assertThat(s.errorMessage()).isNull();
        assertThat(s.pausedFrom()).isNull();
        assertThat(s.drainEvents()).isEmpty();
    }

    // ── Sprint-1 short-circuit path ──────────────────────────────────────────

    @Test
    void startMigration_fromPending_transitionsToMigrating() {
        WorkflowSession s = pending();
        s.startMigration();
        assertThat(s.status()).isEqualTo(SessionStatus.MIGRATING);
    }

    @Test
    void complete_fromMigrating_transitionsToDone() {
        WorkflowSession s = pending();
        s.startMigration();
        s.complete();
        assertThat(s.status()).isEqualTo(SessionStatus.DONE);
        assertThat(s.status().isTerminal()).isTrue();
    }

    // ── full workflow path ────────────────────────────────────────────────────

    @Test
    void fullPath_pendingToContextAnalysedToPlanReadyToMigratingToDone() {
        MigrationPlan plan = MigrationPlan.empty("proj-1");
        WorkflowSession s = pending();

        s.beginContextAnalysis();
        assertThat(s.status()).isEqualTo(SessionStatus.CONTEXT_ANALYSED);

        s.completePlan(plan);
        assertThat(s.status()).isEqualTo(SessionStatus.PLAN_READY);
        assertThat(s.plan()).isEqualTo(plan);

        s.requestApproval();
        assertThat(s.status()).isEqualTo(SessionStatus.AWAITING_APPROVAL);

        s.startMigration();
        assertThat(s.status()).isEqualTo(SessionStatus.MIGRATING);

        s.startValidation(3);
        assertThat(s.status()).isEqualTo(SessionStatus.VALIDATING);

        s.complete();
        assertThat(s.status()).isEqualTo(SessionStatus.DONE);
    }

    // ── domain events ─────────────────────────────────────────────────────────

    @Test
    void beginContextAnalysis_emitsSessionStarted() {
        WorkflowSession s = pending();
        s.beginContextAnalysis();
        List<Object> events = s.drainEvents();
        assertThat(events).hasSize(1)
                .first().isInstanceOf(SessionStarted.class);
        SessionStarted e = (SessionStarted) events.get(0);
        assertThat(e.jobId()).isEqualTo("job-1");
        assertThat(e.projectId()).isEqualTo("proj-1");
    }

    @Test
    void completePlan_emitsPlanReady() {
        MigrationPlan plan = MigrationPlan.empty("proj-1");
        WorkflowSession s = pending();
        s.beginContextAnalysis();
        s.drainEvents();                 // clear SessionStarted
        s.completePlan(plan);
        List<Object> events = s.drainEvents();
        assertThat(events).hasSize(1)
                .first().isInstanceOf(PlanReady.class);
        assertThat(((PlanReady) events.get(0)).plan()).isEqualTo(plan);
    }

    @Test
    void requestApproval_emitsApprovalRequested() {
        WorkflowSession s = pending();
        s.beginContextAnalysis();
        s.completePlan(MigrationPlan.empty("proj-1"));
        s.drainEvents();
        s.requestApproval();
        assertThat(s.drainEvents()).hasSize(1)
                .first().isInstanceOf(ApprovalRequested.class);
    }

    @Test
    void startValidation_emitsMigrationCompleted() {
        WorkflowSession s = pending();
        s.startMigration();
        s.startValidation(5);
        List<Object> events = s.drainEvents();
        assertThat(events).hasSize(1)
                .first().isInstanceOf(MigrationCompleted.class);
        assertThat(((MigrationCompleted) events.get(0)).fileCount()).isEqualTo(5);
    }

    @Test
    void fail_emitsSessionFailed_withReason() {
        WorkflowSession s = pending();
        s.startMigration();
        s.fail("AI down");
        assertThat(s.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(s.errorMessage()).isEqualTo("AI down");
        List<Object> events = s.drainEvents();
        assertThat(events).hasSize(1)
                .first().isInstanceOf(SessionFailed.class);
        assertThat(((SessionFailed) events.get(0)).reason()).isEqualTo("AI down");
    }

    @Test
    void pause_emitsSessionPaused_andStoresPausedFrom() {
        WorkflowSession s = pending();
        s.startMigration();
        s.pause();
        assertThat(s.status()).isEqualTo(SessionStatus.PAUSED);
        assertThat(s.pausedFrom()).isEqualTo(SessionStatus.MIGRATING);
        assertThat(s.drainEvents()).hasSize(1)
                .first().isInstanceOf(SessionPaused.class);
    }

    @Test
    void resume_restoresPausedFromState() {
        WorkflowSession s = pending();
        s.startMigration();
        s.pause();
        s.drainEvents();
        s.resume();
        assertThat(s.status()).isEqualTo(SessionStatus.MIGRATING);
        assertThat(s.pausedFrom()).isNull();
    }

    // ── drainEvents clears list ───────────────────────────────────────────────

    @Test
    void drainEvents_clearsInternalList() {
        WorkflowSession s = pending();
        s.fail("boom");
        assertThat(s.drainEvents()).hasSize(1);
        assertThat(s.drainEvents()).isEmpty();
    }

    // ── invalid transitions ───────────────────────────────────────────────────

    @Test
    void complete_fromPending_throws() {
        assertThatThrownBy(() -> pending().complete())
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("DONE");
    }

    @Test
    void startValidation_fromPending_throws() {
        assertThatThrownBy(() -> pending().startValidation(1))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void fail_fromDone_throws() {
        WorkflowSession s = pending();
        s.startMigration();
        s.complete();
        assertThatThrownBy(() -> s.fail("too late"))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("DONE")
                .hasMessageContaining("FAILED");
    }

    @Test
    void fail_fromFailed_throws() {
        WorkflowSession s = pending();
        s.fail("first failure");
        assertThatThrownBy(() -> s.fail("second"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void resume_whenNotPaused_throws() {
        assertThatThrownBy(() -> pending().resume())
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void pause_fromDone_throws() {
        WorkflowSession s = pending();
        s.startMigration();
        s.complete();
        assertThatThrownBy(s::pause)
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    // ── terminal states ───────────────────────────────────────────────────────

    @Test
    void isTerminal_trueForDoneAndFailed() {
        assertThat(SessionStatus.DONE.isTerminal()).isTrue();
        assertThat(SessionStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    void isTerminal_falseForNonTerminalStates() {
        for (SessionStatus s : SessionStatus.values()) {
            if (s != SessionStatus.DONE && s != SessionStatus.FAILED) {
                assertThat(s.isTerminal()).as("Expected %s to be non-terminal", s).isFalse();
            }
        }
    }

    // ── completePlan null guard ───────────────────────────────────────────────

    @Test
    void completePlan_withNull_throws() {
        WorkflowSession s = pending();
        s.beginContextAnalysis();
        assertThatThrownBy(() -> s.completePlan(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── auto-pause circuit-breaker (#71) ──────────────────────────────────────

    @Test
    void handleAgentFailure_belowThreshold_fails_andReturnsFalse() {
        WorkflowSession s = pending();
        s.startMigration();
        boolean paused = s.handleAgentFailure("boom", 3);
        assertThat(paused).isFalse();
        assertThat(s.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(s.consecutiveAgentErrors()).isEqualTo(1);
    }

    @Test
    void handleAgentFailure_atThreshold_pauses_andReturnsTrue() {
        WorkflowSession s = pending();
        s.startMigration();
        boolean paused = s.handleAgentFailure("boom", 1);
        assertThat(paused).isTrue();
        assertThat(s.status()).isEqualTo(SessionStatus.PAUSED);
        assertThat(s.consecutiveAgentErrors()).isEqualTo(1);
    }

    @Test
    void handleAgentFailure_resumedSession_accumulatesErrorsAcrossAttempts() {
        WorkflowSession s = pending();
        s.startMigration();
        s.pause();              // manually paused
        s.drainEvents();
        s.resume();             // back to MIGRATING

        boolean paused = s.handleAgentFailure("err", 2); // count=1, threshold=2 → fail
        assertThat(paused).isFalse();
        assertThat(s.consecutiveAgentErrors()).isEqualTo(1);
    }

    @Test
    void resetAgentErrors_setsCounterToZero() {
        WorkflowSession s = pending();
        s.startMigration();
        s.handleAgentFailure("err", 5);
        // start a new session (since the first one is now FAILED) to test reset
        WorkflowSession s2 = pending();
        s2.startMigration();
        s2.resetAgentErrors();
        assertThat(s2.consecutiveAgentErrors()).isEqualTo(0);
    }

    @Test
    void consecutiveAgentErrors_startsAtZero() {
        assertThat(pending().consecutiveAgentErrors()).isZero();
    }
}
