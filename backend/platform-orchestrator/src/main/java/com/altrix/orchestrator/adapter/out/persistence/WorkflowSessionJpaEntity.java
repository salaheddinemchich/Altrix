package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
