package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.blueprint.DetectedStack;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.blueprint.SemanticGraph;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-level adapter behaviour: confirms it maps domain ↔ JPA entity
 * correctly and routes both ports through the same JPA repository.
 * The JPA mapping itself is exercised by Flyway + Hibernate at boot —
 * not worth running an in-memory DB just for that here.
 */
class ProjectBlueprintPersistenceAdapterTest {

    private final ProjectBlueprintJpaRepository jpa = mock(ProjectBlueprintJpaRepository.class);
    private final ProjectBlueprintPersistenceAdapter adapter =
            new ProjectBlueprintPersistenceAdapter(jpa);

    private ProjectBlueprint sample(String sessionUuidStr) {
        return new ProjectBlueprint(
                "proj-1",
                sessionUuidStr,
                Instant.parse("2026-01-15T10:00:00Z"),
                ProjectBlueprint.CURRENT_SCHEMA_VERSION,
                DetectedStack.unknown(),
                List.of(),
                List.of(),
                SemanticGraph.empty(),
                List.of(),
                List.of(),
                List.of());
    }

    @Test
    void saveMapsFieldsOntoEntity() {
        ProjectBlueprint blueprint = sample("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        adapter.save(blueprint);

        ArgumentCaptor<ProjectBlueprintJpaEntity> captor =
                ArgumentCaptor.forClass(ProjectBlueprintJpaEntity.class);
        verify(jpa).save(captor.capture());
        ProjectBlueprintJpaEntity entity = captor.getValue();

        assertThat(entity.getSessionId())
                .isEqualTo(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        assertThat(entity.getProjectId()).isEqualTo("proj-1");
        assertThat(entity.getGeneratedAt()).isEqualTo(blueprint.generatedAt());
        assertThat(entity.getSchemaVersion()).isEqualTo((short) ProjectBlueprint.CURRENT_SCHEMA_VERSION);
        assertThat(entity.getBlueprint()).isEqualTo(blueprint);
    }

    @Test
    void findBySessionIdUnwrapsEntity() {
        UUID id = UUID.randomUUID();
        ProjectBlueprint blueprint = sample(id.toString());
        ProjectBlueprintJpaEntity entity = ProjectBlueprintJpaEntity.builder()
                .sessionId(id)
                .projectId(blueprint.projectId())
                .blueprint(blueprint)
                .generatedAt(blueprint.generatedAt())
                .schemaVersion((short) blueprint.schemaVersion())
                .build();
        when(jpa.findById(id)).thenReturn(Optional.of(entity));

        Optional<ProjectBlueprint> result = adapter.findBySessionId(new WorkflowSessionId(id));

        assertThat(result).contains(blueprint);
    }

    @Test
    void findReturnsEmptyWhenAbsent() {
        UUID id = UUID.randomUUID();
        when(jpa.findById(id)).thenReturn(Optional.empty());

        assertThat(adapter.findBySessionId(new WorkflowSessionId(id))).isEmpty();
        assertThat(adapter.findForSession(new WorkflowSessionId(id))).isEmpty();
    }

    @Test
    void bothReadPortsResolveToTheSameRow() {
        UUID id = UUID.randomUUID();
        ProjectBlueprint blueprint = sample(id.toString());
        ProjectBlueprintJpaEntity entity = ProjectBlueprintJpaEntity.builder()
                .sessionId(id)
                .projectId(blueprint.projectId())
                .blueprint(blueprint)
                .generatedAt(blueprint.generatedAt())
                .schemaVersion((short) blueprint.schemaVersion())
                .build();
        when(jpa.findById(id)).thenReturn(Optional.of(entity));

        WorkflowSessionId wsid = new WorkflowSessionId(id);
        assertThat(adapter.findBySessionId(wsid)).contains(blueprint);
        assertThat(adapter.findForSession(wsid)).contains(blueprint);
        verify(jpa, times(2)).findById(id);
    }

    @Test
    void findFileSliceDelegatesToBlueprintLookup() {
        // Default-method path on the read-side port.  Easiest to verify
        // that nothing in our adapter overrides it incorrectly.
        UUID id = UUID.randomUUID();
        when(jpa.findById(id)).thenReturn(Optional.empty());
        assertThat(adapter.findFileSlice(new WorkflowSessionId(id), "any.java")).isEmpty();
    }

    @Test
    void deleteRoutesToJpa() {
        UUID id = UUID.randomUUID();
        adapter.deleteBySessionId(new WorkflowSessionId(id));
        verify(jpa).deleteById(id);
    }

    @Test
    void stringConvenienceOverloadParsesUuid() {
        UUID id = UUID.randomUUID();
        when(jpa.findById(id)).thenReturn(Optional.empty());

        // Calls the default String overload on the repository port.
        assertThat(adapter.findBySessionId(id.toString())).isEmpty();
        verify(jpa).findById(id);
    }

    @Test
    void saveDoesNotInvokeJpaTwiceForOneCall() {
        ProjectBlueprint blueprint = sample(UUID.randomUUID().toString());
        adapter.save(blueprint);
        verify(jpa, times(1)).save(any(ProjectBlueprintJpaEntity.class));
        verifyNoMoreInteractions(jpa);
    }
}
