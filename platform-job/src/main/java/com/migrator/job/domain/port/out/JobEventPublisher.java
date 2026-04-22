package com.migrator.job.domain.port.out;

import com.migrator.job.domain.model.MigrationJob;

/**
 * Secondary port — publishes job domain events.
 * The domain never imports Kafka.
 */
public interface JobEventPublisher {

    /** Published when a job transitions to PENDING — triggers the orchestrator. */
    void publishJobCreated(MigrationJob job);

    /** Published when a job reaches a terminal state. */
    void publishJobCompleted(MigrationJob job);
}
