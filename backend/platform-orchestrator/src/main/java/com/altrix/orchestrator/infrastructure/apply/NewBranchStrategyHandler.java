package com.altrix.orchestrator.infrastructure.apply;

import com.altrix.orchestrator.domain.model.apply.BranchFileChange;
import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyDecision;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyOutcome;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;
import com.altrix.orchestrator.domain.port.out.RepositoryProviderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Create a new branch from the repo's default branch HEAD, commit the
 * migrated files on it, and stop there.  No PR is opened, no merge
 * happens.  The user picks this strategy when they want to land the
 * migration somewhere visible but review/merge offline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewBranchStrategyHandler implements BranchStrategyHandler {

    private final RepositoryProviderPort provider;

    @Override
    public BranchStrategy strategy() {
        return BranchStrategy.NEW_BRANCH;
    }

    @Override
    public MigrationApplyResult apply(MigrationApplyDecision decision, String accessToken,
                                      String repoFullName, List<BranchFileChange> changes) {
        String baseSha = provider.getBranchHeadSha(accessToken, repoFullName, decision.baseBranch());
        if (baseSha == null) {
            throw new RepositoryProviderPort.RepositoryOperationException(
                    "Base branch '" + decision.baseBranch() + "' not found on the remote.");
        }
        provider.createBranch(accessToken, repoFullName, decision.targetBranchName(), baseSha);
        String commitSha = provider.commitFiles(
                accessToken, repoFullName, decision.targetBranchName(), changes, decision.commitMessage());
        log.info("[NewBranchStrategy] committed {} → {}@{}", commitSha, repoFullName, decision.targetBranchName());
        return new MigrationApplyResult(
                MigrationApplyOutcome.BRANCH_CREATED,
                decision.targetBranchName(),
                commitSha,
                null, null, null,
                "Branch '" + decision.targetBranchName() + "' created and migration committed.",
                Instant.now());
    }
}
