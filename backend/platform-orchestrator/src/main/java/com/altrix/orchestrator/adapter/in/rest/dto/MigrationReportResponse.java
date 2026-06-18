package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.common.domain.model.MigrationReport;
import com.altrix.common.domain.model.ReportSummary;

import java.time.Instant;

/**
 * REST shape for the deterministic migration report (#129).
 *
 * @param projectId   the project this report belongs to.
 * @param content     full Markdown report — same string Agent 5 produced.
 * @param generatedAt when the report was produced.
 * @param summary     structured companion to {@code content} — the machine-readable form.
 */
public record MigrationReportResponse(
        String projectId,
        String content,
        Instant generatedAt,
        ReportSummary summary
) {
    public static MigrationReportResponse from(MigrationReport r) {
        return new MigrationReportResponse(r.projectId(), r.content(), r.generatedAt(), r.summary());
    }
}
