package com.altrix.orchestrator.domain.port.in;

import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * Step 1 of the apply workflow: return what the actor can do against
 * the target project's repository.  Drives the frontend's
 * branch-strategy picker and is re-validated server-side at the final
 * {@code apply} call.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #check(String, String)} — by project id, used in flows
 *       that know the project but no session yet (pre-migration UX).</li>
 *   <li>{@link #checkForSession(WorkflowSessionId, String)} — by session
 *       id, used in the post-migration confirmation flow where the
 *       controller already has a session and would otherwise have to
 *       fetch the project id itself (a domain leak).</li>
 * </ul>
 */
public interface CheckRepositoryAccessUseCase {

    RepositoryAccess check(String projectId, String actorUserId);

    RepositoryAccess checkForSession(WorkflowSessionId sessionId, String actorUserId);
}
