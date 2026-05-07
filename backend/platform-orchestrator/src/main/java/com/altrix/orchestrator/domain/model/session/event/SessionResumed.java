package com.altrix.orchestrator.domain.model.session.event;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

public record SessionResumed(WorkflowSessionId sessionId, String jobId, SessionStatus resumedTo) {
    public static SessionResumed of(WorkflowSessionId id, String jobId, SessionStatus resumedTo) {
        return new SessionResumed(id, jobId, resumedTo);
    }
}
