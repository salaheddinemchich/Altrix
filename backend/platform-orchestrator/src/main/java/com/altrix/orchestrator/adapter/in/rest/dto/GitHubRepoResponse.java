package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.github.GitHubRepo;

public record GitHubRepoResponse(
        long id,
        String name,
        String fullName,
        String defaultBranch,
        boolean privateRepo,
        String description,
        String htmlUrl,
        String language
) {
    public static GitHubRepoResponse from(GitHubRepo repo) {
        return new GitHubRepoResponse(
                repo.id(), repo.name(), repo.fullName(), repo.defaultBranch(),
                repo.privateRepo(), repo.description(), repo.htmlUrl(), repo.language()
        );
    }
}
