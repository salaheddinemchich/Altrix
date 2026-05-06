package com.altrix.orchestrator.domain.model.session.event;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import java.time.Instant;

/** Fired when a new WorkflowSession enters CONTEXT_ANALYSED for the first time. */
public record SessionStarted(WorkflowSessionId sessionId, String jobId, String projectId, Instant occurredAt) {

    public static SessionStarted of(WorkflowSessionId id, String jobId, String projectId) {
        return new SessionStarted(id, jobId, projectId, Instant.now());
    }
}
