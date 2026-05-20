package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;

import java.time.Instant;

/**
 * REST response DTO for session lifecycle endpoints.
 *
 * <p>Never exposes the full {@link WorkflowSession} aggregate or its events —
 * only the fields needed by the Angular client.  Includes the AI's proposed
 * {@link MigrationPlanResponse} from the AWAITING_APPROVAL gate onwards (#10)
 * so reviewers can see (and later edit) what they are about to approve.
 */
public record SessionStatusResponse(
        String sessionId,
        String jobId,
        String projectId,
        SessionStatus status,
        SessionStatus pausedFrom,
        String errorMessage,
        Instant updatedAt,
        MigrationPlanResponse plan
) {
    public static SessionStatusResponse from(WorkflowSession s) {
        return new SessionStatusResponse(
                s.id().value().toString(),
                s.jobId(),
                s.projectId(),
                s.status(),
                s.pausedFrom(),
                s.errorMessage(),
                s.updatedAt(),
                MigrationPlanResponse.from(s.plan()));
    }
}
