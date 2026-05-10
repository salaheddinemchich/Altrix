package com.altrix.orchestrator.domain.model.session.event;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;

/**
 * Fired when a WorkflowSession is paused — carries the state it was paused from for resume.
 */
public record SessionPaused(WorkflowSessionId sessionId, String jobId,
                            SessionStatus pausedFrom, Instant occurredAt) {

    public static SessionPaused of(WorkflowSessionId id, String jobId, SessionStatus pausedFrom) {
        return new SessionPaused(id, jobId, pausedFrom, Instant.now());
    }
}
