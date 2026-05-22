package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface WorkflowSessionJpaRepository extends JpaRepository<WorkflowSessionJpaEntity, UUID> {

    Optional<WorkflowSessionJpaEntity> findByJobId(String jobId);

    List<WorkflowSessionJpaEntity> findByStatus(SessionStatus status);

    List<WorkflowSessionJpaEntity> findByStatusAndUpdatedAtBefore(SessionStatus status, Instant before);

    // ── Projection queries for paginated list views ───────────────────────────
    // Uses SessionSummaryProjection to exclude plan/migrated_files JSONB columns,
    // cutting per-page I/O by ~90% compared to loading full entities.

    Page<SessionSummaryProjection> findProjectedBy(Pageable pageable);

    Page<SessionSummaryProjection> findProjectedByStatus(SessionStatus status, Pageable pageable);

    // ── #131 — Cross-session aggregates for /api/v1/reports/summary ───────────

    /** Total session count by status — feeds success rate + per-status counts. */
    @Query("SELECT s.status, COUNT(s) FROM WorkflowSessionJpaEntity s GROUP BY s.status")
    List<Object[]> countByStatus();

    /**
     * Average completion duration in seconds for DONE sessions
     * (updated_at - created_at).  Returns null when no DONE sessions exist
     * yet — callers must map that to 0.
     */
    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (updated_at - created_at))) " +
                   "FROM workflow_sessions WHERE status = 'DONE'",
            nativeQuery = true)
    Double averageDoneDurationSeconds();
}
