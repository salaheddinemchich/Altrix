package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
