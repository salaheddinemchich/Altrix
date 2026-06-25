package com.altrix.orchestrator.domain.model.apply;

import java.util.List;
import java.util.Set;

/**
 * Snapshot of what the current user can do against the target
 * repository, used to decide which {@link BranchStrategy} options to
 * offer in the UI and to validate the user's choice server-side.
 *
 * <p>{@code defaultBranch} comes from the provider (never hardcoded as
 * "main") so projects with non-standard default branches behave
 * correctly.
 *
 * @param fullName            "owner/repo".
 * @param defaultBranch       the repository's default branch as the provider reports it.
 * @param permission          the authenticated user's permission level.
 * @param canCreateBranch     true when the user may push a new ref.
 * @param canMergeDefault     true when the user may merge into the default branch
 *                            (false when branch protection requires review even for admins).
 * @param availableStrategies the subset of {@link BranchStrategy} values the user is
 *                            allowed to choose, computed by the domain service.
 * @param branches            every branch on the repo, so the user can push the
 *                            migration to a base other than the default branch.
 */
public record RepositoryAccess(
        String fullName,
        String defaultBranch,
        RepositoryPermission permission,
        boolean canCreateBranch,
        boolean canMergeDefault,
        Set<BranchStrategy> availableStrategies,
        List<String> branches
) {
    public RepositoryAccess {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName must be present");
        }
        if (defaultBranch == null || defaultBranch.isBlank()) {
            throw new IllegalArgumentException("defaultBranch must be present");
        }
        if (permission == null) permission = RepositoryPermission.NONE;
        availableStrategies = availableStrategies != null ? Set.copyOf(availableStrategies) : Set.of();
        branches = branches != null ? List.copyOf(branches) : List.of();
    }
}
