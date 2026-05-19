package com.altrix.project.domain.port.out;

import com.altrix.project.domain.model.RepositorySnapshot;

import java.nio.file.Path;

/**
 * Driven port — clones a remote Git repository into a local workspace.
 *
 * <p>Issue #74: implementations must be framework-agnostic from the caller's
 * perspective; the domain has no knowledge of JGit or any specific transport.
 *
 * <p>Implementations are expected to:
 * <ul>
 *   <li>Support HTTPS public clones and HTTPS + Personal Access Token for private repos</li>
 *   <li>Honour a per-call request to do a shallow clone for large repos</li>
 *   <li>Reject repos exceeding the configured size limit with
 *       {@link com.altrix.common.exception.RepositoryIngestionException}</li>
 *   <li>Translate every transport-level or auth-level failure into
 *       {@link com.altrix.common.exception.RepositoryIngestionException}</li>
 * </ul>
 */
public interface RepositoryIngestionPort {

    /**
     * Clones {@code request.repoUrl()} into a freshly created temp directory and
     * checks out {@code request.branch()} (or HEAD when null).
     *
     * @return a {@link RepositorySnapshot} pointing at the working tree on disk
     * @throws com.altrix.common.exception.RepositoryIngestionException on any
     *         transport, auth, branch-resolution, or size-limit failure
     */
    RepositorySnapshot clone(CloneRequest request);

    /**
     * Recursively deletes a previously cloned workspace.  Idempotent — silently
     * no-ops when the path does not exist.  Implementations should never throw.
     */
    void cleanup(Path workspacePath);

    /**
     * Parameter object for {@link #clone(CloneRequest)}.
     *
     * @param repoUrl     HTTPS URL of the remote repository — required
     * @param branch      branch or tag ref to check out, or {@code null} for HEAD
     * @param accessToken HTTPS token for private repos, or {@code null} for public
     * @param shallow     when {@code true}, perform a shallow clone (depth = 50)
     */
    record CloneRequest(
            String repoUrl,
            String branch,
            String accessToken,
            boolean shallow
    ) {
        public CloneRequest {
            if (repoUrl == null || repoUrl.isBlank()) {
                throw new IllegalArgumentException("repoUrl must not be blank");
            }
        }

        /** Convenience: public-repo full clone of HEAD. */
        public static CloneRequest publicHead(String repoUrl) {
            return new CloneRequest(repoUrl, null, null, false);
        }
    }
}
