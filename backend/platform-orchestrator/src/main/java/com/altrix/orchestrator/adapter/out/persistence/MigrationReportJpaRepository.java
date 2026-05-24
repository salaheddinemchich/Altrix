package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MigrationReportJpaRepository extends JpaRepository<MigrationReportJpaEntity, UUID> {

    /** Latest report (max version) for a session — feeds GET /sessions/{id}/report. */
    Optional<MigrationReportJpaEntity> findFirstBySessionIdOrderByVersionDesc(UUID sessionId);

    /** Full append-only history, newest version first — feeds GET /sessions/{id}/reports. */
    List<MigrationReportJpaEntity> findBySessionIdOrderByVersionDesc(UUID sessionId);

    /** Specific version — feeds GET /sessions/{id}/reports/{version}. */
    Optional<MigrationReportJpaEntity> findBySessionIdAndVersion(UUID sessionId, int version);

    /**
     * Current max version for a session, 0 when none exist.  Used to
     * compute the next-version number on append.
     */
    @Query("SELECT COALESCE(MAX(r.version), 0) FROM MigrationReportJpaEntity r WHERE r.sessionId = :sessionId")
    int maxVersion(UUID sessionId);

    /**
     * Full-text search over report content (#163).
     *
     * <p>Uses Postgres' {@code plainto_tsquery} which parses the user
     * input as PLAIN text (terms AND'd together, special characters
     * escaped) — completely safe against tsquery injection.
     * {@code ts_headline} renders a highlighted snippet, {@code ts_rank}
     * scores relevance.  Results ordered by rank DESC then generated_at
     * DESC so newer hits win ties.
     */
    @Query(value = """
            SELECT
              r.report_id    AS reportId,
              r.session_id   AS sessionId,
              r.version      AS version,
              r.project_id   AS projectId,
              r.generated_at AS generatedAt,
              ts_headline('english', r.content, q,
                          'StartSel=<b>, StopSel=</b>, MaxFragments=2, MaxWords=20, MinWords=5')
                             AS snippet,
              ts_rank(r.content_tsv, q) AS rank
            FROM migration_reports r,
                 plainto_tsquery('english', :query) q
            WHERE r.content_tsv @@ q
            ORDER BY rank DESC, r.generated_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM migration_reports r, plainto_tsquery('english', :query) q
            WHERE r.content_tsv @@ q
            """,
            nativeQuery = true)
    org.springframework.data.domain.Page<MigrationReportSearchHit> searchByQuery(
            @Param("query") String query, Pageable pageable);
}
