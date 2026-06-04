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

    // ── Apply workflow (#PR-feature) ─────────────────────────────────────────
    // Once a session reaches DONE, the user picks a BranchStrategy and the
    // server issues a one-shot confirmation token.  The /apply endpoint
    // re-validates everything against these columns.  All nullable — the
    // apply workflow only attaches once the user opts in.

    @Column(name = "apply_strategy", length = 20)
    private String applyStrategy;

    @Column(name = "apply_branch_name", length = 255)
    private String applyBranchName;

    @Column(name = "apply_base_branch", length = 255)
    private String applyBaseBranch;

    @Column(name = "apply_commit_message", columnDefinition = "TEXT")
    private String applyCommitMessage;

    @Column(name = "apply_pr_title", columnDefinition = "TEXT")
    private String applyPrTitle;

    @Column(name = "apply_pr_body", columnDefinition = "TEXT")
    private String applyPrBody;

    @Column(name = "apply_confirmation_token")
    private java.util.UUID applyConfirmationToken;

    @Column(name = "apply_confirmation_expires")
    private Instant applyConfirmationExpires;

    /** STRATEGY_SET | APPLIED | CANCELLED — never the outcome enum (that's separate). */
    @Column(name = "apply_status", length = 20)
    private String applyStatus;

    @Column(name = "apply_outcome", length = 20)
    private String applyOutcome;

    @Column(name = "apply_result_url", length = 500)
    private String applyResultUrl;

    @Column(name = "apply_result_sha", length = 64)
    private String applyResultSha;

    @Column(name = "apply_actor_user_id", length = 64)
    private String applyActorUserId;

    @Column(name = "apply_completed_at")
    private Instant applyCompletedAt;
}
