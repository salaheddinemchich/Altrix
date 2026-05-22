package com.altrix.orchestrator.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for the {@code migration_reports} table (#129).  Keyed by the
 * session UUID, with an ON DELETE CASCADE FK to {@code workflow_sessions}.
 */
@Entity
@Table(name = "migration_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationReportJpaEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
