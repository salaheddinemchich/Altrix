package com.altrix.orchestrator.domain.port.in;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.UUID;

/**
 * Step 2: the user picks a branch strategy and (when needed) a branch
 * name.  Persists the choice on the session and returns a one-shot
 * {@code confirmationToken} the user must echo back in the final apply
 * call.  Server-validated end of "Final Confirmation Gate".
 */
public interface ChooseBranchStrategyUseCase {

    Outcome choose(WorkflowSessionId sessionId,
                   BranchStrategy strategy,
                   String targetBranchName,
                   String baseBranch,
                   String commitMessage,
                   String prTitle,
                   String prBody,
                   String actorUserId);

    /** What the endpoint returns: the token the UI must echo on apply. */
    record Outcome(UUID confirmationToken, BranchStrategy strategy, String targetBranchName, String baseBranch) {}
}
