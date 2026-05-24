package com.altrix.orchestrator.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * Spring Data projection — one row of a full-text search result over
 * {@code migration_reports} (#163).
 *
 * <p>Carries identity + a {@code snippet} highlighting the search term
 * (rendered by Postgres' {@code ts_headline}) and a {@code rank} the
 * UI can use to order ties or render relevance.
 */
public interface MigrationReportSearchHit {

    UUID getReportId();
    UUID getSessionId();
    Integer getVersion();
    String getProjectId();
    Instant getGeneratedAt();

    /** {@code ts_headline} output — HTML-ish: {@code <b>term</b>}. */
    String getSnippet();

    /** {@code ts_rank} score — higher = more relevant. */
    Float getRank();
}
