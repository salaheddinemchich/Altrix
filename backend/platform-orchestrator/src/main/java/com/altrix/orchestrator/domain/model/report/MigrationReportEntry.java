package com.altrix.orchestrator.domain.model.report;

import com.altrix.common.domain.model.MigrationReport;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the append-only migration-report history (#162).
 *
 * <p>Wraps the framework-free {@code MigrationReport} value object with
 * its persistence-layer metadata (synthetic id, monotonic per-session
 * version).  Lives in the orchestrator domain rather than
 * {@code platform-common} because versioning is an orchestrator concern;
 * other services that consume MigrationReport over the wire don't care
 * about the row identity.
 *
 * @param reportId    synthetic primary key — stable across the report's lifetime.
 * @param sessionId   the {@code workflow_sessions} row this report belongs to.
 * @param version     monotonic per-session; 1 for the first report, +1 per re-run.
 * @param report      the framework-free MigrationReport value.
 */
public record MigrationReportEntry(
        UUID reportId,
        UUID sessionId,
        int version,
        MigrationReport report
) {
    public String projectId()       { return report.projectId(); }
    public String content()         { return report.content(); }
    public Instant generatedAt()    { return report.generatedAt(); }
}
