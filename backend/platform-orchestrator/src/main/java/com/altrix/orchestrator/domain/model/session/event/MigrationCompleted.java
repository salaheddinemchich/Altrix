package com.altrix.orchestrator.domain.model.session.event;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;

/**
 * Fired when the CoreMigratorAgent finishes and the session transitions to VALIDATING.
 */
public record MigrationCompleted(WorkflowSessionId sessionId, String jobId, int fileCount, Instant occurredAt) {

    public static MigrationCompleted of(WorkflowSessionId id, String jobId, int fileCount) {
        return new MigrationCompleted(id, jobId, fileCount, Instant.now());
    }
}
