package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.apply.BranchFileChange;
import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;

import java.util.List;

/**
 * Provider-agnostic repository operations the domain needs to apply a
 * migration result.  GitHub today, GitLab / Bitbucket as future
 * adapters.  Keeping the domain unaware of any provider lets us add
 * one by adding a single adapter — no domain change.
 *
 * <p>All operations take the user's OAuth access token explicitly
 * (never read from a thread-local) so the call is auditable to the
 * actor that initiated it.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Be idempotent where the spec requires (creating a branch that
 *       already exists must not fail).</li>
 *   <li>Never log or echo the access token.</li>
 *   <li>Translate provider-specific errors into the documented
 *       {@code RepositoryOperationException} so the domain can react
 *       uniformly.</li>
 * </ul>
 */
public interface RepositoryProviderPort {

    /** Provider id this adapter speaks to ("github", "gitlab", …). */
    String providerId();

    /**
     * Snapshot of what the authenticated user can do on the repo —
     * permission level, default branch, computed strategy set.
     */
    RepositoryAccess getAccess(String accessToken, String repoFullName);

    /** Returns the head commit SHA of the named branch. */
    String getBranchHeadSha(String accessToken, String repoFullName, String branchName);

    /**
     * Creates a branch at {@code baseSha}.  Idempotent: if a branch
     * with that name already exists at the same SHA, returns its SHA;
     * if it exists at a different SHA, throws so the caller can ask
     * the user for a different name.
     */
    String createBranch(String accessToken, String repoFullName, String newBranchName, String baseSha);

    /**
     * Commits {@code changes} on top of {@code branchName} with the
     * supplied message and returns the new commit SHA.  Implementations
     * SHOULD batch the changes into a single commit (one tree update).
     */
    String commitFiles(String accessToken, String repoFullName, String branchName,
                       List<BranchFileChange> changes, String commitMessage);

    /**
     * Opens a Pull Request from {@code headBranch} into {@code baseBranch}
     * and returns the resulting PR reference.
     */
    PullRequestRef createPullRequest(String accessToken, String repoFullName,
                                     String headBranch, String baseBranch,
                                     String title, String body);

    /**
     * Merges {@code headBranch} into {@code baseBranch} and returns the
     * merge commit SHA.  Only invoked on the DIRECT_MERGE strategy
     * after the user's explicit confirmation.
     */
    String mergeBranch(String accessToken, String repoFullName,
                       String headBranch, String baseBranch, String commitMessage);

    /** Pull-Request identifier returned from {@link #createPullRequest}. */
    record PullRequestRef(int number, String url) {
        public PullRequestRef {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        }
    }

    /** Translated, domain-friendly version of any provider failure. */
    class RepositoryOperationException extends RuntimeException {
        public RepositoryOperationException(String message) { super(message); }
        public RepositoryOperationException(String message, Throwable cause) { super(message, cause); }
    }
}
