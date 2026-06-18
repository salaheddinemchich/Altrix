package com.altrix.common.domain.model;

import java.io.Serializable;

import java.time.Instant;

/**
 * Output of {@code ReportGeneratorAgent} (Agent 5) — the user-facing report.
 *
 * <p>{@code content} is the full Markdown technical report (its first section
 * doubles as the executive summary). {@code summary} is the same data in
 * structured form — returned as-is over REST, it IS the machine-readable
 * JSON report. Both are produced deterministically; no AI is involved in
 * generating either.
 */
public record MigrationReport(

        String projectId,

        /** Full Markdown report — see {@code MigrationReportBuilder} for section layout. */
        String content,

        /** When the report was produced. */
        Instant generatedAt,

        /** Structured companion to {@code content} — see {@link ReportSummary}. */
        ReportSummary summary

) implements Serializable {
    public MigrationReport {
        if (content == null) content = "";
        if (generatedAt == null) generatedAt = Instant.EPOCH;
        if (summary == null) summary = ReportSummary.empty();
    }

    public static MigrationReport empty(String projectId) {
        return new MigrationReport(projectId, "", Instant.now(), ReportSummary.empty());
    }
}
