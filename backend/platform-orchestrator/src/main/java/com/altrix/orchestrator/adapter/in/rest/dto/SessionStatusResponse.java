package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;

import java.time.Instant;

/**
 * REST response DTO for session lifecycle endpoints.
 *
 * <p>Never exposes the full {@link WorkflowSession} aggregate or its events —
 * only the fields needed by the Angular client.
 */
public record SessionStatusResponse(
        String sessionId,
        String jobId,
        String projectId,
        SessionStatus status,
        SessionStatus pausedFrom,
        String errorMessage,
        Instant updatedAt
) {
    public static SessionStatusResponse from(WorkflowSession s) {
        return new SessionStatusResponse(
                s.id().value().toString(),
                s.jobId(),
                s.projectId(),
                s.status(),
                s.pausedFrom(),
                s.errorMessage(),
                s.updatedAt());
    }
}
