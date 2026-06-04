package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyOutcome;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter that touches only the {@code apply_*} columns + the
 * persisted {@code migrated_files} blob on {@code workflow_sessions}.
 *
 * <p>Uses the existing {@link WorkflowSessionJpaRepository}; JPA's
 * identity map keeps reads and writes consistent with the migration
 * aggregate even though it lives in a separate domain port.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationApplyStateAdapter implements MigrationApplyStatePort {

    private final WorkflowSessionJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<State> find(WorkflowSessionId sessionId) {
        return repository.findById(sessionId.value()).map(this::toState);
    }

    @Override
    @Transactional
    public void saveStrategyChoice(WorkflowSessionId sessionId,
                                   BranchStrategy strategy,
                                   String branchName,
                                   String baseBranch,
                                   String commitMessage,
                                   String prTitle,
                                   String prBody,
                                   UUID confirmationToken,
                                   Instant confirmationExpires,
                                   String actorUserId) {
        WorkflowSessionJpaEntity e = mustLoad(sessionId);
        e.setApplyStrategy(strategy.name());
        e.setApplyBranchName(branchName);
        e.setApplyBaseBranch(baseBranch);
        e.setApplyCommitMessage(commitMessage);
        e.setApplyPrTitle(prTitle);
        e.setApplyPrBody(prBody);
        e.setApplyConfirmationToken(confirmationToken);
        e.setApplyConfirmationExpires(confirmationExpires);
        e.setApplyStatus("STRATEGY_SET");
        e.setApplyOutcome(null);
        e.setApplyResultUrl(null);
        e.setApplyResultSha(null);
        e.setApplyActorUserId(actorUserId);
        e.setApplyCompletedAt(null);
        repository.save(e);
    }

    @Override
    @Transactional
    public void saveOutcome(WorkflowSessionId sessionId,
                            MigrationApplyOutcome outcome,
                            String resultUrl,
                            String resultSha,
                            Instant completedAt) {
        WorkflowSessionJpaEntity e = mustLoad(sessionId);
        e.setApplyStatus("APPLIED");
        e.setApplyOutcome(outcome.name());
        e.setApplyResultUrl(resultUrl);
        e.setApplyResultSha(resultSha);
        e.setApplyConfirmationToken(null);
        e.setApplyConfirmationExpires(null);
        e.setApplyCompletedAt(completedAt != null ? completedAt : Instant.now());
        repository.save(e);
    }

    @Override
    @Transactional
    public void markCancelled(WorkflowSessionId sessionId, Instant cancelledAt) {
        WorkflowSessionJpaEntity e = mustLoad(sessionId);
        e.setApplyStatus("CANCELLED");
        e.setApplyOutcome(MigrationApplyOutcome.CANCELLED.name());
        e.setApplyConfirmationToken(null);
        e.setApplyConfirmationExpires(null);
        e.setApplyCompletedAt(cancelledAt != null ? cancelledAt : Instant.now());
        repository.save(e);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private WorkflowSessionJpaEntity mustLoad(WorkflowSessionId id) {
        return repository.findById(id.value())
                .orElseThrow(() -> new IllegalStateException("Session not found: " + id));
    }

    private State toState(WorkflowSessionJpaEntity e) {
        BranchStrategy strategy = e.getApplyStrategy() != null
                ? BranchStrategy.valueOf(e.getApplyStrategy()) : null;
        MigrationApplyOutcome outcome = e.getApplyOutcome() != null
                ? MigrationApplyOutcome.valueOf(e.getApplyOutcome()) : null;
        return new State(
                new WorkflowSessionId(e.getId()),
                e.getProjectId(),
                e.getStatus() != null ? e.getStatus().name() : null,
                strategy,
                e.getApplyBranchName(),
                e.getApplyBaseBranch(),
                e.getApplyCommitMessage(),
                e.getApplyPrTitle(),
                e.getApplyPrBody(),
                e.getApplyConfirmationToken(),
                e.getApplyConfirmationExpires(),
                e.getApplyStatus(),
                outcome,
                e.getApplyResultUrl(),
                e.getApplyResultSha(),
                e.getApplyActorUserId(),
                e.getApplyCompletedAt(),
                e.getMigratedFiles() != null ? List.copyOf(e.getMigratedFiles()) : List.of());
    }
}
