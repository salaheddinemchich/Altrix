package com.altrix.orchestrator.infrastructure.apply;

import com.altrix.orchestrator.domain.model.apply.BranchFileChange;
import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyDecision;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;

import java.util.List;

/**
 * Strategy-pattern hook: one handler per {@link BranchStrategy} value.
 * The {@code MigrationApplyService} discovers all handlers via Spring
 * autowiring and dispatches by {@link #strategy()} — adding a new
 * strategy is one new bean, not an edit to a central {@code switch}.
 */
public interface BranchStrategyHandler {

    /** Which strategy this handler executes. */
    BranchStrategy strategy();

    /**
     * Perform the strategy.  The caller has already verified the
     * confirmation token, the actor's permissions, and that the
     * migration result is ready.
     *
     * @param decision     fully-validated user decision (strategy / branch / messages).
     * @param accessToken  OAuth token to authenticate against the provider.
     * @param repoFullName "owner/repo".
     * @param changes      the migrated file content to commit.
     */
    MigrationApplyResult apply(MigrationApplyDecision decision,
                               String accessToken,
                               String repoFullName,
                               List<BranchFileChange> changes);
}
