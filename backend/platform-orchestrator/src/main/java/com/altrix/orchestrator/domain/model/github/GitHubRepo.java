package com.altrix.orchestrator.domain.model.github;

/**
 * Value object representing a GitHub repository returned from the GitHub API.
 * No framework dependencies — pure domain record.
 */
public record GitHubRepo(
        long id,
        String name,
        String fullName,
        String defaultBranch,
        boolean privateRepo,
        String description,
        String htmlUrl,
        String language
) {}
