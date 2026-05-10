package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.session.SessionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight Spring Data projection for paginated session lists.
 *
 * <p>Excludes the heavy JSONB columns ({@code plan}, {@code migrated_files})
 * which can each be tens of kilobytes per row. At 20 rows per page, loading
 * full entities wastes significant I/O and heap even when the UI only shows
 * status + timestamps in the list view.
 *
 * <p>Spring Data resolves this at query time using a {@code SELECT} that names
 * only the projected fields, so the JSONB columns are never read from disk.
 */
public interface SessionSummaryProjection {
    UUID getId();
    String getJobId();
    String getProjectId();
    SessionStatus getStatus();
    SessionStatus getPausedFrom();
    int getConsecutiveAgentErrors();
    Instant getCreatedAt();
    Instant getUpdatedAt();
}
