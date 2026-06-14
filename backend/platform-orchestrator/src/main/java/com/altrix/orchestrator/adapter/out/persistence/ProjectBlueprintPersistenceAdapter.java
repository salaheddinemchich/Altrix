package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.ProjectBlueprintPort;
import com.altrix.orchestrator.domain.port.out.ProjectBlueprintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Single Spring bean that implements both ports for
 * {@code project_blueprints}.  Splitting them in production wouldn't
 * gain anything — they share the same underlying JPA repository — but
 * keeping two ports lets the migrator code stay scoped to reads only
 * (compile-time guarantee against accidental writes).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectBlueprintPersistenceAdapter
        implements ProjectBlueprintRepository, ProjectBlueprintPort {

    private final ProjectBlueprintJpaRepository jpaRepository;

    // ── Write side ─────────────────────────────────────────────────────────

    @Override
    public void save(ProjectBlueprint blueprint) {
        UUID sessionUuid = UUID.fromString(blueprint.sessionId());
        ProjectBlueprintJpaEntity entity = ProjectBlueprintJpaEntity.builder()
                .sessionId(sessionUuid)
                .projectId(blueprint.projectId())
                .blueprint(blueprint)
                .generatedAt(blueprint.generatedAt())
                .schemaVersion((short) blueprint.schemaVersion())
                .build();
        jpaRepository.save(entity);
        log.debug("Saved ProjectBlueprint for session {} ({} files)",
                blueprint.sessionId(), blueprint.files().size());
    }

    // ── Read side (both ports converge here) ──────────────────────────────

    @Override
    public Optional<ProjectBlueprint> findBySessionId(WorkflowSessionId sessionId) {
        return jpaRepository.findById(sessionId.value())
                .map(ProjectBlueprintJpaEntity::getBlueprint);
    }

    @Override
    public Optional<ProjectBlueprint> findForSession(WorkflowSessionId sessionId) {
        return findBySessionId(sessionId);
    }

    @Override
    public void deleteBySessionId(WorkflowSessionId sessionId) {
        jpaRepository.deleteById(sessionId.value());
    }
}
