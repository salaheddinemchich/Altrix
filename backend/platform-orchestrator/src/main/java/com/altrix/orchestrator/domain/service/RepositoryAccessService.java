package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.CheckRepositoryAccessUseCase;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort;
import com.altrix.orchestrator.domain.port.out.OAuthTokenLookupPort;
import com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort;
import com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort.ProjectMetadata;
import com.altrix.orchestrator.domain.port.out.RepositoryProviderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implements step 1 of the apply workflow: resolves the project's
 * repository, looks up the actor's OAuth token for that provider, and
 * asks the provider what the user can do.  Pure domain — no Spring or
 * HTTP types cross this boundary.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #check(String, String)} — by project id, used in flows
 *       where the caller already knows the project.</li>
 *   <li>{@link #checkForSession(WorkflowSessionId, String)} — by session
 *       id, looks up the session's project id internally so the caller
 *       (typically a REST controller) doesn't have to reach into the
 *       persistence layer.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class RepositoryAccessService implements CheckRepositoryAccessUseCase {

    private final ProjectMetadataLookupPort projects;
    private final OAuthTokenLookupPort tokens;
    private final RepositoryProviderPort provider;
    private final MigrationApplyStatePort applyState;

    @Override
    public RepositoryAccess check(String projectId, String actorUserId) {
        ProjectMetadata project = projects.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.repoFullName() == null || project.providerId() == null) {
            throw new IllegalStateException(
                    "Project '" + projectId + "' has no repository — apply workflow not available.");
        }
        String accessToken = tokens.findAccessToken(actorUserId, project.providerId())
                .orElseThrow(() -> new IllegalStateException(
                        "No " + project.providerId() + " OAuth token stored for user " + actorUserId
                        + ". Sign in with " + project.providerId() + " first."));
        return provider.getAccess(accessToken, project.repoFullName());
    }

    @Override
    public RepositoryAccess checkForSession(WorkflowSessionId sessionId, String actorUserId) {
        String projectId = applyState.find(sessionId)
                .map(MigrationApplyStatePort.State::projectId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        return check(projectId, actorUserId);
    }
}
