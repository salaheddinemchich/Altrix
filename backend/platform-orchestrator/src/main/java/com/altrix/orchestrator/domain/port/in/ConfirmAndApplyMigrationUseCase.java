package com.altrix.orchestrator.domain.port.in;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.UUID;

/**
 * Step 4 of the apply workflow: the user has confirmed and is sending
 * us the {@code confirmationToken} we issued earlier.  The service
 * re-validates EVERYTHING against stored state (token match, strategy
 * unchanged, branch name unchanged, session still DONE, actor still
 * has the permission for the strategy).
 *
 * <p>The {@code baseBranch} and {@code commitMessage} that actually get
 * sent to the provider come from the persisted choose-time state — the
 * incoming {@link Request} only carries what the client claims for
 * cross-checking against that stored truth.
 */
public interface ConfirmAndApplyMigrationUseCase {

    MigrationApplyResult apply(Request request);

    /**
     * What the controller passes in.  Only fields the user controls.
     * All trusted state (baseBranch, original branchName at choose
     * time, stored token) is loaded server-side from the session row.
     */
    record Request(
            WorkflowSessionId sessionId,
            BranchStrategy strategy,
            String branchName,
            String commitMessage,
            String prTitle,
            String prBody,
            UUID confirmationToken,
            boolean userApproved,
            String actorUserId
    ) {
        public Request {
            if (sessionId == null)         throw new IllegalArgumentException("sessionId required");
            if (strategy == null)          throw new IllegalArgumentException("strategy required");
            if (confirmationToken == null) throw new IllegalArgumentException("confirmationToken required");
            if (actorUserId == null)       throw new IllegalArgumentException("actorUserId required");
        }
    }
}
