package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.exception.IllegalStateTransitionException;
import com.altrix.orchestrator.domain.exception.SessionNotFoundException;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.ResumeMigrationUseCase;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionManagementServiceTest {

    @Mock
    WorkflowSessionRepository sessionRepository;

    @Mock
    ResumeMigrationUseCase resumeMigration;

    @Mock
    JobStatusUpdatePort jobStatusUpdatePort;

    @Mock
    ProgressNotifierPort progressNotifier;

    /** Runs tasks synchronously so the test asserts the resume hook actually fires. */
    final Executor resumeExecutor = Runnable::run;

    SessionManagementService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new SessionManagementService(sessionRepository, resumeMigration,
                resumeExecutor, jobStatusUpdatePort, progressNotifier);
    }

    // ── approve ───────────────────────────────────────────────────────────────

    @Test
    void approve_transitionsToMigrating() {
        WorkflowSession session = awaitingApproval();
        WorkflowSessionId id = session.id();

        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowSession result = service.approve(id);

        assertThat(result.status()).isEqualTo(SessionStatus.MIGRATING);
        verify(sessionRepository).save(session);
    }

    @Test
    void approve_kicksOffTheResumePipeline() {
        WorkflowSession session = awaitingApproval();
        WorkflowSessionId id = session.id();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(id);

        verify(resumeMigration).resume(id);
    }

    @Test
    void approve_resumeFailure_does_not_break_approval_response() {
        WorkflowSession session = awaitingApproval();
        WorkflowSessionId id = session.id();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("kafka down")).when(resumeMigration).resume(id);

        // Should NOT propagate the resume failure — the REST 200 has already been
        // promised to the client; downstream failure is surfaced via WebSocket.
        WorkflowSession result = service.approve(id);

        assertThat(result.status()).isEqualTo(SessionStatus.MIGRATING);
    }

    @Test
    void approve_sessionNotFound_throws() {
        WorkflowSessionId id = WorkflowSessionId.generate();
        when(sessionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(id))
                .isInstanceOf(SessionNotFoundException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void approve_invalidTransition_throws() {
        WorkflowSession done = doneSession();
        WorkflowSessionId id = done.id();

        when(sessionRepository.findById(id)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.approve(id))
                .isInstanceOf(IllegalStateTransitionException.class);

        verify(sessionRepository, never()).save(any());
    }

    // ── reject ────────────────────────────────────────────────────────────────

    @Test
    void reject_transitionsToFailed_withReason() {
        WorkflowSession session = awaitingApproval();
        WorkflowSessionId id = session.id();

        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowSession result = service.reject(id, "Not acceptable");

        assertThat(result.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(result.errorMessage()).isEqualTo("Not acceptable");
        verify(sessionRepository).save(session);
    }

    // ── pause ─────────────────────────────────────────────────────────────────

    @Test
    void pause_transitionsToPaused_andRecordsPausedFrom() {
        WorkflowSession session = migrating();
        WorkflowSessionId id = session.id();

        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowSession result = service.pause(id);

        assertThat(result.status()).isEqualTo(SessionStatus.PAUSED);
        assertThat(result.pausedFrom()).isEqualTo(SessionStatus.MIGRATING);
    }

    @Test
    void pause_terminalSession_throws() {
        WorkflowSession failed = failedSession();
        when(sessionRepository.findById(failed.id())).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> service.pause(failed.id()))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    // ── resume ────────────────────────────────────────────────────────────────

    @Test
    void resume_restoresPrePausedState() {
        WorkflowSession session = migrating();
        session.pause();
        WorkflowSessionId id = session.id();

        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowSession result = service.resume(id);

        assertThat(result.status()).isEqualTo(SessionStatus.MIGRATING);
        assertThat(result.pausedFrom()).isNull();
    }

    // ── expireStaleApprovals ──────────────────────────────────────────────────

    @Test
    void expireStaleApprovals_failsAllStale_andReturnsCount() {
        WorkflowSession s1 = awaitingApproval();
        WorkflowSession s2 = awaitingApproval();
        Instant cutoff = Instant.now();

        when(sessionRepository.findByStatusAndUpdatedAtBefore(SessionStatus.AWAITING_APPROVAL, cutoff))
                .thenReturn(List.of(s1, s2));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.expireStaleApprovals(cutoff);

        assertThat(count).isEqualTo(2);
        assertThat(s1.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(s2.status()).isEqualTo(SessionStatus.FAILED);
        verify(sessionRepository, times(2)).save(any());
    }

    @Test
    void expireStaleApprovals_noStale_returnsZero() {
        when(sessionRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of());

        assertThat(service.expireStaleApprovals(Instant.now())).isZero();
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void expireStaleApprovals_saveFailure_continuesAndCountsOnlySucceeded() {
        WorkflowSession s1 = awaitingApproval();
        WorkflowSession s2 = awaitingApproval();
        Instant cutoff = Instant.now();

        when(sessionRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(s1, s2));
        when(sessionRepository.save(s1)).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(s2)).thenThrow(new RuntimeException("DB error"));

        int count = service.expireStaleApprovals(cutoff);

        assertThat(count).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static WorkflowSession awaitingApproval() {
        WorkflowSession s = WorkflowSession.create("job-" + System.nanoTime(), "proj-1");
        s.beginContextAnalysis();
        s.completePlan(com.altrix.common.domain.model.MigrationPlan.empty("proj-1"));
        s.requestApproval();
        return s;
    }

    private static WorkflowSession migrating() {
        WorkflowSession s = WorkflowSession.create("job-" + System.nanoTime(), "proj-1");
        s.startMigration();
        return s;
    }

    private static WorkflowSession doneSession() {
        WorkflowSession s = migrating();
        s.complete();
        return s;
    }

    private static WorkflowSession failedSession() {
        WorkflowSession s = migrating();
        s.fail("boom");
        return s;
    }
}
