package com.altrix.job.domain.port.in;

import com.altrix.job.domain.model.MigrationJob;

/**
 * Primary port — CQRS Command side.
 * Used by the orchestrator (via Kafka) to advance job status.
 */
public interface UpdateJobStatusUseCase {

    MigrationJob markAnalyzing(String jobId);
    MigrationJob markMigrating(String jobId);
    MigrationJob markDone(String jobId, String outputStorageKey);
    MigrationJob markFailed(String jobId, String reason);
}
