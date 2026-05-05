package com.altrix.project.domain.port.out;

import com.altrix.project.domain.model.Project;

import java.util.List;
import java.util.Optional;

/**
 * Secondary port — driven side.
 *
 * <p>Defines what the domain needs from a persistence store.
 * The domain service depends on this interface — never on JPA directly.
 * The JPA adapter implements this interface.
 *
 * <p>This interface belongs to the domain layer. The implementation
 * ({@code ProjectPersistenceAdapter}) lives in the adapter layer.
 */
public interface ProjectRepositoryPort {

    /** Persists a new project or updates an existing one. Returns the saved entity. */
    Project save(Project project);

    /** Finds a project by its unique ID. */
    Optional<Project> findById(String projectId);

    /** Finds all projects for a given user, ordered by creation date descending. */
    List<Project> findAllByUserId(String userId);
}
