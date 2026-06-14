package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.Optional;

/**
 * Write- + read-side persistence port for {@link ProjectBlueprint}.
 *
 * <p>The migrator + UI use the lighter-weight {@link ProjectBlueprintPort}
 * for reads; this repository exists for the {@code ProjectMapperAgent}
 * (writes one row per session) and for tests / admin scripts that need
 * full CRUD.
 *
 * <p>Implementations persist to the {@code project_blueprints} table
 * (Flyway V23).  Storage is a single JSONB column — schema evolution
 * happens by bumping {@link ProjectBlueprint#schemaVersion()}.
 */
public interface ProjectBlueprintRepository {

    /**
     * Upsert by {@code sessionId}.  Replaces any existing blueprint for
     * the session — only one is ever kept per workflow run.
     */
    void save(ProjectBlueprint blueprint);

    /** Returns the blueprint for {@code sessionId}, or empty when none exists. */
    Optional<ProjectBlueprint> findBySessionId(WorkflowSessionId sessionId);

    /** Convenience overload accepting a raw String session id. */
    default Optional<ProjectBlueprint> findBySessionId(String sessionId) {
        return findBySessionId(new WorkflowSessionId(java.util.UUID.fromString(sessionId)));
    }

    /**
     * Removes the blueprint for {@code sessionId}.  No-op when absent.
     * Used in tests + when a session is fully purged.
     */
    void deleteBySessionId(WorkflowSessionId sessionId);
}
