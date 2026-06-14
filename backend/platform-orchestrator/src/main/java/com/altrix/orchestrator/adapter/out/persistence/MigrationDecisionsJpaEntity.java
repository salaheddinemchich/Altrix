package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.migration.MigrationDecision;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping for {@code migration_decisions} (Flyway V24) — one row per
 * session, JSONB array of {@link MigrationDecision}.
 */
@Entity
@Table(name = "migration_decisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationDecisionsJpaEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Convert(converter = MigrationDecisionsJsonConverter.class)
    @Column(name = "decisions", nullable = false, columnDefinition = "jsonb")
    private List<MigrationDecision> decisions;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
