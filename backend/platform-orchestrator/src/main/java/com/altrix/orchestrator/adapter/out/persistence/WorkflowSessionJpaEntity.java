package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "workflow_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowSessionJpaEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Optimistic lock counter — Hibernate increments this on every UPDATE.
     * A concurrent modifier holding a stale version throws ObjectOptimisticLockingFailureException,
     * which the global exception handler maps to 409 Conflict.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "job_id", nullable = false, unique = true, length = 36)
    private String jobId;

    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SessionStatus status;

    /**
     * JSONB column — serialised by {@link MigrationPlanConverter}.
     */
    @Column(columnDefinition = "jsonb")
    private MigrationPlan plan;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * State the session was in before it was paused — null when not PAUSED.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "paused_from", length = 30)
    private SessionStatus pausedFrom;

    @Column(name = "consecutive_agent_errors", nullable = false)
    private int consecutiveAgentErrors;

    @Convert(converter = MigratedFilesConverter.class)
    @Column(name = "migrated_files", columnDefinition = "jsonb")
    private List<MigratedFile> migratedFiles;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Approval audit (#125 / #126) ─────────────────────────────────────────
    // Who approved/rejected, when, and which kind of decision.  All nullable
    // — populated only once an approval gate has been answered.

    @Column(name = "decided_by", length = 255)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_kind", length = 16)
    private String decisionKind;
}
