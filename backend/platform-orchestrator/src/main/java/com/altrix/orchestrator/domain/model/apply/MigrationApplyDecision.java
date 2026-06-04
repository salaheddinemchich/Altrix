package com.altrix.orchestrator.domain.model.apply;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.UUID;

/**
 * What the user has chosen and is about to confirm.  Built once the
 * branch strategy is set and re-validated server-side at the final
 * apply call.
 *
 * <p>{@code confirmationToken} is a single-use UUID issued by the server
 * when the strategy is set; the apply endpoint accepts the request only
 * when the token matches the one stored on the session row.  This
 * satisfies the spec's "Confirmation step must be server-validated and
 * not rely solely on frontend controls" requirement — a user who
 * fabricates an apply call without first going through the picker has
 * no token to send.
 *
 * @param sessionId          the migration session this decision belongs to.
 * @param strategy           which branch-handling path to execute.
 * @param targetBranchName   for NEW_BRANCH / PULL_REQUEST — the branch to create.
 *                           Must NOT equal the default branch.
 * @param baseBranch         the branch to commit on top of / merge into (= repo default branch).
 * @param commitMessage      message used for the migration commit.
 * @param prTitle            for PULL_REQUEST only.
 * @param prBody             for PULL_REQUEST only.
 * @param confirmationToken  server-issued token; must match the session's pending token.
 * @param actorUserId        the user submitting the confirmation (audit trail).
 * @param userApproved       must be {@code true}; mirrors the frontend "I have
 *                           reviewed and approve" checkbox.
 */
public record MigrationApplyDecision(
        WorkflowSessionId sessionId,
        BranchStrategy strategy,
        String targetBranchName,
        String baseBranch,
        String commitMessage,
        String prTitle,
        String prBody,
        UUID confirmationToken,
        String actorUserId,
        boolean userApproved
) {
    public MigrationApplyDecision {
        if (sessionId == null)        throw new IllegalArgumentException("sessionId is required");
        if (strategy == null)         throw new IllegalArgumentException("strategy is required");
        if (baseBranch == null || baseBranch.isBlank())
                                      throw new IllegalArgumentException("baseBranch is required");
        if (confirmationToken == null)
                                      throw new IllegalArgumentException("confirmationToken is required");
        if (actorUserId == null || actorUserId.isBlank())
                                      throw new IllegalArgumentException("actorUserId is required");
        if (!userApproved)
                                      throw new IllegalStateException("userApproved must be true at construction");
        if (strategy != BranchStrategy.DIRECT_MERGE
                && (targetBranchName == null || targetBranchName.isBlank())) {
            throw new IllegalArgumentException("targetBranchName is required for " + strategy);
        }
        if (commitMessage == null || commitMessage.isBlank()) {
            throw new IllegalArgumentException("commitMessage is required");
        }
    }
}
