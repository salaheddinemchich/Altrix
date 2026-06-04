package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyOutcome;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads / writes the {@code apply_*} columns on {@code workflow_sessions}
 * plus the migrated-files blob that a successful migration left there.
 * Separate port from {@link WorkflowSessionRepository} so the apply
 * workflow can evolve without disturbing the migration aggregate.
 */
public interface MigrationApplyStatePort {

    /** Snapshot of the apply state and the migration output for one session. */
    record State(
            WorkflowSessionId sessionId,
            String projectId,
            String sessionStatus,
            BranchStrategy strategy,
            String branchName,
            String baseBranch,
            String commitMessage,
            String prTitle,
            String prBody,
            UUID confirmationToken,
            Instant confirmationExpires,
            String applyStatus,           // STRATEGY_SET | APPLIED | CANCELLED
            MigrationApplyOutcome outcome,
            String resultUrl,
            String resultSha,
            String actorUserId,
            Instant completedAt,
            List<MigratedFile> migratedFiles
    ) {}

    Optional<State> find(WorkflowSessionId sessionId);

    /**
     * Persists the strategy choice + token after the user picks but
     * before they confirm.  Idempotent: re-issuing a token replaces the
     * previous one (the user changed their mind).
     */
    void saveStrategyChoice(WorkflowSessionId sessionId,
                            BranchStrategy strategy,
                            String branchName,
                            String baseBranch,
                            String commitMessage,
                            String prTitle,
                            String prBody,
                            UUID confirmationToken,
                            Instant confirmationExpires,
                            String actorUserId);

    /** Marks the apply as completed (success or failure) and clears the token. */
    void saveOutcome(WorkflowSessionId sessionId,
                     MigrationApplyOutcome outcome,
                     String resultUrl,
                     String resultSha,
                     Instant completedAt);

    /** Marks the apply as cancelled and clears the token. */
    void markCancelled(WorkflowSessionId sessionId, Instant cancelledAt);
}
