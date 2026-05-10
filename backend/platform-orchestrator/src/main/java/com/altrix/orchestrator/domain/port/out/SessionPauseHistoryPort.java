package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.session.SessionPauseRecord;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;
import java.util.List;

/**
 * Secondary port — appends and queries pause/resume history for a session (#72).
 */
public interface SessionPauseHistoryPort {

    void record(WorkflowSessionId sessionId, SessionStatus pausedFrom, Instant pausedAt);

    /**
     * Marks the most recent open (resumedAt=null) entry for this session as resumed.
     */
    void markResumed(WorkflowSessionId sessionId, Instant resumedAt);

    List<SessionPauseRecord> findBySessionId(WorkflowSessionId sessionId);
}
