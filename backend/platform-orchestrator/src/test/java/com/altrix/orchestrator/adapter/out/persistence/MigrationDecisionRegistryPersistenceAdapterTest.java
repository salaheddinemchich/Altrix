package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.migration.MigrationDecision;
import com.altrix.orchestrator.domain.model.migration.MigrationDecisionRegistry;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Adapter behaviour + the JSON converter round-trip in one place
 * (the converter is exercised implicitly by the entity field type, but
 * here we test the adapter's load→append→save merge logic directly).
 */
class MigrationDecisionRegistryPersistenceAdapterTest {

    private final MigrationDecisionsJpaRepository jpa = mock(MigrationDecisionsJpaRepository.class);
    private final MigrationDecisionRegistryPersistenceAdapter adapter =
            new MigrationDecisionRegistryPersistenceAdapter(jpa);

    private WorkflowSessionId sid() {
        return new WorkflowSessionId(UUID.randomUUID());
    }

    @Test
    void findReturnsEmptyRegistryWhenAbsent() {
        WorkflowSessionId id = sid();
        when(jpa.findById(id.value())).thenReturn(Optional.empty());

        MigrationDecisionRegistry r = adapter.findForSession(id);

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.sessionId()).isEqualTo(id.value().toString());
    }

    @Test
    void findUnwrapsStoredDecisions() {
        WorkflowSessionId id = sid();
        MigrationDecision d = MigrationDecision.replaceType("Pubsub", "KafkaProducer", "x");
        when(jpa.findById(id.value())).thenReturn(Optional.of(
                MigrationDecisionsJpaEntity.builder()
                        .sessionId(id.value())
                        .decisions(List.of(d))
                        .updatedAt(java.time.Instant.now())
                        .build()));

        MigrationDecisionRegistry r = adapter.findForSession(id);

        assertThat(r.decisions()).containsExactly(d);
        assertThat(r.typeReplacement("Pubsub")).contains("KafkaProducer");
    }

    @Test
    void recordAppendsToExistingDecisions() {
        WorkflowSessionId id = sid();
        MigrationDecision existing = MigrationDecision.replaceType("A", "B", "first");
        when(jpa.findById(id.value())).thenReturn(Optional.of(
                MigrationDecisionsJpaEntity.builder()
                        .sessionId(id.value())
                        .decisions(new java.util.ArrayList<>(List.of(existing)))
                        .updatedAt(java.time.Instant.now())
                        .build()));

        MigrationDecision added = MigrationDecision.replaceType("C", "D", "second");
        adapter.record(id, added);

        ArgumentCaptor<MigrationDecisionsJpaEntity> captor =
                ArgumentCaptor.forClass(MigrationDecisionsJpaEntity.class);
        verify(jpa).save(captor.capture());
        assertThat(captor.getValue().getDecisions()).containsExactly(existing, added);
    }

    @Test
    void recordOnFreshSessionStartsANewList() {
        WorkflowSessionId id = sid();
        when(jpa.findById(id.value())).thenReturn(Optional.empty());

        adapter.record(id, MigrationDecision.replaceType("A", "B", "x"));

        ArgumentCaptor<MigrationDecisionsJpaEntity> captor =
                ArgumentCaptor.forClass(MigrationDecisionsJpaEntity.class);
        verify(jpa).save(captor.capture());
        assertThat(captor.getValue().getDecisions()).hasSize(1);
        assertThat(captor.getValue().getSessionId()).isEqualTo(id.value());
    }

    @Test
    void recordAllWithEmptyListIsNoOp() {
        WorkflowSessionId id = sid();
        adapter.recordAll(id, List.of());
        verifyNoInteractions(jpa);
    }

    @Test
    void clearDeletesByPrimaryKey() {
        WorkflowSessionId id = sid();
        adapter.clear(id);
        verify(jpa).deleteById(id.value());
    }
}
