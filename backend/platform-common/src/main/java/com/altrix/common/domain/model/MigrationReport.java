package com.altrix.common.domain.model;

import java.io.Serializable;

import java.time.Instant;

/**
 * Output of {@code ReportGeneratorAgent} (Agent 5) — the user-facing report.
 *
 * <p>Real implementation (Markdown/HTML/PDF artifacts, signed download URLs,
 * etc.) lands in issue #29. This shape is enough for the orchestrator to
 * persist a report identifier alongside the job.
 */
public record MigrationReport(

        String projectId,

        /** Body of the report in whatever format the generator chose (md/html/json). */
        String content,

        /** When the report was produced. */
        Instant generatedAt

) implements Serializable {
    public MigrationReport {
        if (content     == null) content     = "";
        if (generatedAt == null) generatedAt = Instant.EPOCH;
    }

    public static MigrationReport empty(String projectId) {
        return new MigrationReport(projectId, "", Instant.now());
    }
}
