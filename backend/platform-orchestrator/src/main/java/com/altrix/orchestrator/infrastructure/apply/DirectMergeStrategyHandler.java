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
 * Commit on a transient migration branch and immediately merge it into
 * the default branch.  Only invoked when the user has explicit ADMIN
 * permission AND ticked the confirmation box; the service-level guard
 * blocks this strategy for non-admins.
 *
 * <p>Even on a direct-merge we still commit to a working branch first
 * so the operation is atomic-ish — if the merge call fails, we leave a
 * branch the user can inspect / re-merge, rather than a dangling commit
 * on top of the default branch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectMergeStrategyHandler implements BranchStrategyHandler {

    private final RepositoryProviderPort provider;

    @Override
    public BranchStrategy strategy() {
        return BranchStrategy.DIRECT_MERGE;
    }

    @Override
    public MigrationApplyResult apply(MigrationApplyDecision decision, String accessToken,
                                      String repoFullName, List<BranchFileChange> changes) {
        String baseSha = provider.getBranchHeadSha(accessToken, repoFullName, decision.baseBranch());
        if (baseSha == null) {
            throw new RepositoryProviderPort.RepositoryOperationException(
                    "Base branch '" + decision.baseBranch() + "' not found on the remote.");
        }
        // Even direct-merge uses a working branch so the merge is atomic.
        String workingBranch = decision.targetBranchName(); // service supplies a generated name when null
        provider.createBranch(accessToken, repoFullName, workingBranch, baseSha);
        String commitSha = provider.commitFiles(
                accessToken, repoFullName, workingBranch, changes, decision.commitMessage());
        String mergeSha = provider.mergeBranch(
                accessToken, repoFullName, workingBranch, decision.baseBranch(), decision.commitMessage());
        log.info("[DirectMergeStrategy] merged {} → {}@{} ({})",
                workingBranch, repoFullName, decision.baseBranch(), mergeSha);
        return new MigrationApplyResult(
                MigrationApplyOutcome.MERGED_TO_MAIN,
                workingBranch,
                commitSha,
                null, null,
                mergeSha,
                "Merged into '" + decision.baseBranch() + "' (" + mergeSha + ").",
                Instant.now());
    }
}
