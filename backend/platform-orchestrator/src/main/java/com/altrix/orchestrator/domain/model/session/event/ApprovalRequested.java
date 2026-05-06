package com.altrix.orchestrator.domain.model.session.event;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;

/**
 * Fired when the session enters AWAITING_APPROVAL — notifies reviewers.
 */
public record ApprovalRequested(WorkflowSessionId sessionId, String jobId, Instant occurredAt) {

    public static ApprovalRequested of(WorkflowSessionId id, String jobId) {
        return new ApprovalRequested(id, jobId, Instant.now());
    }
}
