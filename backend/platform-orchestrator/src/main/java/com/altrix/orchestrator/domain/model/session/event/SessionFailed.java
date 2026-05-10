package com.altrix.orchestrator.domain.model.session.event;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;

/**
 * Fired when a WorkflowSession transitions to FAILED.
 */
public record SessionFailed(WorkflowSessionId sessionId, String jobId, String reason, Instant occurredAt) {

    public static SessionFailed of(WorkflowSessionId id, String jobId, String reason) {
        return new SessionFailed(id, jobId, reason, Instant.now());
    }
}
