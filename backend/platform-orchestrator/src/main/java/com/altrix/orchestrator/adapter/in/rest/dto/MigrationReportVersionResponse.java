package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.report.MigrationReportEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * REST shape for one row of the migration-report history (#162).
 *
 * <p>Carries the same fields as {@link MigrationReportResponse} plus the
 * persistence-layer metadata (synthetic id, per-session version) so the
 * frontend can render a versions selector and link individual versions.
 */
public record MigrationReportVersionResponse(
        UUID reportId,
        UUID sessionId,
        int version,
        String projectId,
        String content,
        Instant generatedAt
) {
    public static MigrationReportVersionResponse from(MigrationReportEntry e) {
        return new MigrationReportVersionResponse(
                e.reportId(),
                e.sessionId(),
                e.version(),
                e.projectId(),
                e.content(),
                e.generatedAt()
        );
    }
}
