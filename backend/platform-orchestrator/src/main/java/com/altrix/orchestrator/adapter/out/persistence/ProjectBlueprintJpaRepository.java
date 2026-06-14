package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ProjectBlueprintJpaEntity}.
 *
 * <p>No custom queries needed — find/save/delete by primary key
 * ({@code session_id}) is the only access pattern.
 */
public interface ProjectBlueprintJpaRepository
        extends JpaRepository<ProjectBlueprintJpaEntity, UUID> {
}
