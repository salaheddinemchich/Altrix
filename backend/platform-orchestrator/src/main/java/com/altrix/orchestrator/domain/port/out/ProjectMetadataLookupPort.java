package com.altrix.orchestrator.domain.port.out;

import java.util.Optional;

/**
 * Resolves a project id (which is what the workflow_sessions row carries)
 * into the metadata the apply workflow needs from the canonical source —
 * platform-project.  Kept as a port so the domain doesn't import any
 * HTTP / REST types and the lookup can be swapped for a local cache /
 * gRPC client later.
 */
public interface ProjectMetadataLookupPort {

    Optional<ProjectMetadata> findById(String projectId);

    /**
     * @param projectId   the canonical project id.
     * @param repoFullName  e.g. "owner/repo" — null when the project was uploaded
     *                      as a ZIP and isn't backed by a remote repository.
     * @param trackedBranch  default branch the project was cloned at, never
     *                       null on remote-backed projects.
     * @param providerId  "github" / "gitlab" — null for non-VCS projects.
     */
    record ProjectMetadata(String projectId, String repoFullName, String trackedBranch, String providerId) {}
}
