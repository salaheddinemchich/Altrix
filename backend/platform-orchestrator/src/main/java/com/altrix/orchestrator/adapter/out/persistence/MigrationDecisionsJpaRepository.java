package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link MigrationDecisionsJpaEntity}.
 * Access is always by primary key ({@code session_id}).
 */
public interface MigrationDecisionsJpaRepository
        extends JpaRepository<MigrationDecisionsJpaEntity, UUID> {
}
