package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface WorkflowSessionJpaRepository extends JpaRepository<WorkflowSessionJpaEntity, UUID> {

    Optional<WorkflowSessionJpaEntity> findByJobId(String jobId);

    List<WorkflowSessionJpaEntity> findByStatus(SessionStatus status);

    List<WorkflowSessionJpaEntity> findByStatusAndUpdatedAtBefore(SessionStatus status, Instant before);
}
