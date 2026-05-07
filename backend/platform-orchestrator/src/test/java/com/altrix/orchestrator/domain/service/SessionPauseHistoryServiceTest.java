package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.model.session.SessionPauseRecord;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.SessionPauseHistoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPauseHistoryServiceTest {

    @Mock SessionPauseHistoryPort pauseHistoryPort;

    @Test
    void record_isCalledWithCorrectArgs() {
        WorkflowSessionId id = WorkflowSessionId.generate();
        Instant now = Instant.now();

        pauseHistoryPort.record(id, SessionStatus.MIGRATING, now);

        verify(pauseHistoryPort).record(eq(id), eq(SessionStatus.MIGRATING), eq(now));
    }

    @Test
    void markResumed_isCalledAfterResume() {
        WorkflowSessionId id = WorkflowSessionId.generate();
        Instant now = Instant.now();

        pauseHistoryPort.markResumed(id, now);

        verify(pauseHistoryPort).markResumed(eq(id), eq(now));
    }

    @Test
    void findBySessionId_returnsAllEntries() {
        WorkflowSessionId id = WorkflowSessionId.generate();
        Instant pausedAt = Instant.now().minusSeconds(60);
        Instant resumedAt = Instant.now();

        when(pauseHistoryPort.findBySessionId(id)).thenReturn(List.of(
                new SessionPauseRecord(1L, id, SessionStatus.MIGRATING, pausedAt, resumedAt),
                new SessionPauseRecord(2L, id, SessionStatus.AWAITING_APPROVAL, pausedAt.minusSeconds(300), null)
        ));

        List<SessionPauseRecord> records = pauseHistoryPort.findBySessionId(id);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).resumedAt()).isEqualTo(resumedAt);
        assertThat(records.get(1).resumedAt()).isNull();
        assertThat(records.get(1).pausedFrom()).isEqualTo(SessionStatus.AWAITING_APPROVAL);
    }

    @Test
    void pauseSession_emitsPausedEvent_pausedFromPreserved() {
        WorkflowSession s = WorkflowSession.create("job-1", "proj-1");
        s.startMigration();
        s.pause();

        assertThat(s.pausedFrom()).isEqualTo(SessionStatus.MIGRATING);
        assertThat(s.status()).isEqualTo(SessionStatus.PAUSED);
        assertThat(s.drainEvents()).hasSize(1);
    }

    @Test
    void resumeSession_emitsResumedEvent() {
        WorkflowSession s = WorkflowSession.create("job-1", "proj-1");
        s.startMigration();
        s.pause();
        s.drainEvents();
        s.resume();

        assertThat(s.status()).isEqualTo(SessionStatus.MIGRATING);
        assertThat(s.drainEvents()).hasSize(1);
    }
}
