package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.apply.RepositoryPermission;

import java.util.List;

/**
 * REST shape of {@link RepositoryAccess}.  Strategies are sent as the
 * domain enum names so the frontend can use them as switch keys
 * without translation.
 */
public record RepositoryAccessResponse(
        String fullName,
        String defaultBranch,
        RepositoryPermission permission,
        boolean canCreateBranch,
        boolean canMergeDefault,
        List<BranchStrategy> availableStrategies
) {
    public static RepositoryAccessResponse from(RepositoryAccess a) {
        // List preserves a stable order for the UI (NEW_BRANCH first, then PR, then DIRECT_MERGE).
        List<BranchStrategy> ordered = List.of(BranchStrategy.NEW_BRANCH, BranchStrategy.PULL_REQUEST, BranchStrategy.DIRECT_MERGE)
                .stream().filter(a.availableStrategies()::contains).toList();
        return new RepositoryAccessResponse(
                a.fullName(),
                a.defaultBranch(),
                a.permission(),
                a.canCreateBranch(),
                a.canMergeDefault(),
                ordered);
    }
}
