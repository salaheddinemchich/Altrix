package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface MigrationApplyAuditJpaRepository
        extends JpaRepository<MigrationApplyAuditJpaEntity, UUID> {

    /** Audit timeline for a session, oldest first. */
    List<MigrationApplyAuditJpaEntity> findBySessionIdOrderByOccurredAtAsc(UUID sessionId);
}
