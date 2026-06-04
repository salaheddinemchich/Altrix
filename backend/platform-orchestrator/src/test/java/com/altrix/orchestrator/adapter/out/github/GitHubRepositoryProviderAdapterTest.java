package com.altrix.orchestrator.adapter.out.github;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the GitHub permission → BranchStrategy translation.
 * The full network round-trip is integration-level and lives elsewhere.
 */
class GitHubRepositoryProviderAdapterTest {

    @Test
    void readOnlyUser_getsNoStrategies() {
        Set<BranchStrategy> s = GitHubRepositoryProviderAdapter.computeStrategies(false, false);
        assertThat(s).isEmpty();
    }

    @Test
    void writeUser_getsBranchAndPR_butNotDirectMerge() {
        Set<BranchStrategy> s = GitHubRepositoryProviderAdapter.computeStrategies(true, false);
        assertThat(s).containsExactlyInAnyOrder(BranchStrategy.NEW_BRANCH, BranchStrategy.PULL_REQUEST);
        assertThat(s).doesNotContain(BranchStrategy.DIRECT_MERGE);
    }

    @Test
    void adminUser_getsAllThreeStrategies() {
        Set<BranchStrategy> s = GitHubRepositoryProviderAdapter.computeStrategies(true, true);
        assertThat(s).containsExactlyInAnyOrder(
                BranchStrategy.NEW_BRANCH,
                BranchStrategy.PULL_REQUEST,
                BranchStrategy.DIRECT_MERGE);
    }
}
