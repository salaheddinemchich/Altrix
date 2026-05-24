package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.MigrationReport;
import com.altrix.orchestrator.domain.model.report.MigrationReportEntry;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.List;
import java.util.Optional;

/**
 * Secondary port — persists the narrative {@link MigrationReport}s produced
 * by Agent 5 (#129).
 *
 * <p>Append-only since #162: every {@link #save} inserts a new row with
 * {@code version = current max + 1}.  The "latest" view feeds the
 * existing single-report endpoint; the full history feeds the new
 * versions endpoints.
 */
public interface MigrationReportRepository {

    /**
     * Appends a new report version for the session.  Returns the persisted
     * entry (with synthetic id + version assigned).
     */
    MigrationReportEntry save(WorkflowSessionId sessionId, MigrationReport report);

    /** Latest report for the session, or empty when none has been generated. */
    Optional<MigrationReport> findBySessionId(WorkflowSessionId sessionId);

    /**
     * Full append-only history for the session, newest version first.
     * Empty list when no reports exist.
     */
    List<MigrationReportEntry> findAllBySessionId(WorkflowSessionId sessionId);

    /** Specific version of the report for the session. */
    Optional<MigrationReportEntry> findBySessionIdAndVersion(WorkflowSessionId sessionId, int version);
}
