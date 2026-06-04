package com.altrix.orchestrator.adapter.out.github;

import com.altrix.orchestrator.domain.model.apply.BranchFileChange;
import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.apply.RepositoryPermission;
import com.altrix.orchestrator.domain.port.out.RepositoryProviderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * GitHub implementation of {@link RepositoryProviderPort}.  Translates
 * provider-specific concepts (the {@code permissions} block, the Git
 * Data API tree/commit dance, PR creation) into the domain-level
 * operations the apply workflow needs.
 *
 * <p>Stays a thin adapter: every HTTP call is delegated to
 * {@link GitHubApiClient}, which is the only place that knows about
 * GitHub URLs.  This adapter focuses on translation only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubRepositoryProviderAdapter implements RepositoryProviderPort {

    private final GitHubApiClient client;

    @Override
    public String providerId() {
        return "github";
    }

    @Override
    public RepositoryAccess getAccess(String accessToken, String repoFullName) {
        GitHubApiClient.RepoPermissionJson info = client.getRepoPermission(accessToken, repoFullName);
        if (info == null || info.permissions() == null) {
            throw new RepositoryOperationException("GitHub returned no permission data for " + repoFullName);
        }
        RepositoryPermission permission = mapPermission(info.permissions());
        boolean canCreateBranch = info.permissions().push() || info.permissions().admin()
                                  || info.permissions().maintain();
        // Default-branch merge needs admin or the branch protection rules to
        // allow push — we don't introspect protection rules here (extra API
        // call per check), so we use admin as the safe minimum.  False
        // negatives only ever HIDE the DIRECT_MERGE option, which is the
        // correct safer default.
        boolean canMergeDefault = info.permissions().admin();
        return new RepositoryAccess(
                info.fullName(),
                info.defaultBranch(),
                permission,
                canCreateBranch,
                canMergeDefault,
                computeStrategies(canCreateBranch, canMergeDefault));
    }

    @Override
    public String getBranchHeadSha(String accessToken, String repoFullName, String branchName) {
        return client.getBranchHeadSha(accessToken, repoFullName, branchName);
    }

    @Override
    public String createBranch(String accessToken, String repoFullName, String newBranchName, String baseSha) {
        return client.createBranch(accessToken, repoFullName, newBranchName, baseSha);
    }

    @Override
    public String commitFiles(String accessToken, String repoFullName, String branchName,
                              List<BranchFileChange> changes, String commitMessage) {
        List<GitHubApiClient.TreeEntry> entries = new ArrayList<>(changes.size());
        for (BranchFileChange ch : changes) {
            entries.add(new GitHubApiClient.TreeEntry(
                    ch.path(),
                    ch.deletion() ? null : GitHubApiClient.toBase64(ch.content()),
                    ch.deletion()));
        }
        return client.commitFilesAsTree(accessToken, repoFullName, branchName, entries, commitMessage);
    }

    @Override
    public PullRequestRef createPullRequest(String accessToken, String repoFullName,
                                            String headBranch, String baseBranch,
                                            String title, String body) {
        GitHubApiClient.PullRequestJson pr =
                client.createPullRequest(accessToken, repoFullName, headBranch, baseBranch, title, body);
        if (pr == null) throw new RepositoryOperationException("GitHub returned no PR data");
        return new PullRequestRef(pr.number(), pr.htmlUrl());
    }

    @Override
    public String mergeBranch(String accessToken, String repoFullName,
                              String headBranch, String baseBranch, String commitMessage) {
        return client.mergeBranch(accessToken, repoFullName, headBranch, baseBranch, commitMessage);
    }

    // ── translation helpers ─────────────────────────────────────────────────

    private static RepositoryPermission mapPermission(GitHubApiClient.RepoPermissionJson.Permissions p) {
        if (p.admin())    return RepositoryPermission.ADMIN;
        if (p.maintain() || p.push()) return RepositoryPermission.WRITE;
        if (p.pull())     return RepositoryPermission.READ;
        return RepositoryPermission.NONE;
    }

    /** Translates capability flags into the strategy set the UI may offer. */
    static Set<BranchStrategy> computeStrategies(boolean canCreateBranch, boolean canMergeDefault) {
        EnumSet<BranchStrategy> s = EnumSet.noneOf(BranchStrategy.class);
        if (canCreateBranch) {
            s.add(BranchStrategy.NEW_BRANCH);
            s.add(BranchStrategy.PULL_REQUEST);
        }
        if (canMergeDefault) s.add(BranchStrategy.DIRECT_MERGE);
        return s;
    }
}
