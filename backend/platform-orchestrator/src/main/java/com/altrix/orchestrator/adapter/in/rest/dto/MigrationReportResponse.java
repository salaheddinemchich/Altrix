package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.common.domain.model.MigrationReport;

import java.time.Instant;

/**
 * REST shape for the narrative migration report (#129).
 *
 * @param projectId   the project this report belongs to.
 * @param content     Markdown body — same string Agent 5 produced.
 * @param generatedAt when the report was produced.
 */
public record MigrationReportResponse(
        String projectId,
        String content,
        Instant generatedAt
) {
    public static MigrationReportResponse from(MigrationReport r) {
        return new MigrationReportResponse(r.projectId(), r.content(), r.generatedAt());
    }
}
