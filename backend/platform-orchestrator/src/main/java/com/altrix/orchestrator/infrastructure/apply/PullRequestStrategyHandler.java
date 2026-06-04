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
 * Create branch + commit + open a Pull Request targeting the default
 * branch.  The user picks this when repository policy requires
 * reviewer approval before any code lands on the default branch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestStrategyHandler implements BranchStrategyHandler {

    private final RepositoryProviderPort provider;

    @Override
    public BranchStrategy strategy() {
        return BranchStrategy.PULL_REQUEST;
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
        RepositoryProviderPort.PullRequestRef pr = provider.createPullRequest(
                accessToken, repoFullName,
                decision.targetBranchName(), decision.baseBranch(),
                decision.prTitle(), decision.prBody());
        log.info("[PullRequestStrategy] opened PR #{} on {}", pr.number(), repoFullName);
        return new MigrationApplyResult(
                MigrationApplyOutcome.PR_CREATED,
                decision.targetBranchName(),
                commitSha,
                pr.url(),
                pr.number(),
                null,
                "PR #" + pr.number() + " opened against '" + decision.baseBranch() + "'.",
                Instant.now());
    }
}
