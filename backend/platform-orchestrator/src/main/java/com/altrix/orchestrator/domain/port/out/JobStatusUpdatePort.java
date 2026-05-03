package com.altrix.orchestrator.domain.port.out;

/**
 * Secondary port — tells platform-job to update job status.
 * The domain never imports Kafka directly.
 */
public interface JobStatusUpdatePort {

    void markAnalyzing(String jobId);

    void markMigrating(String jobId);

    void markDone(String jobId, String outputStorageKey);

    void markFailed(String jobId, String reason);
}
