package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.MigrationReport;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.Optional;

/**
 * Secondary port — persists the narrative {@link MigrationReport} produced
 * by Agent 5 (#129).  One row per workflow session.
 */
public interface MigrationReportRepository {

    /**
     * Stores or replaces the report for the given session.  Idempotent —
     * a re-run of the migration overwrites the previous row.
     */
    void save(WorkflowSessionId sessionId, MigrationReport report);

    /** Returns the persisted report for a session, or empty when none exists. */
    Optional<MigrationReport> findBySessionId(WorkflowSessionId sessionId);
}
