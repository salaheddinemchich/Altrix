package com.altrix.orchestrator.domain.model.apply;

/**
 * Capability the authenticated user holds on the target repository,
 * derived from the provider's permission model (GitHub's role string,
 * GitLab's access level, …).  Ordered from least to most privileged.
 */
public enum RepositoryPermission {
    /** No access — repository is private and the user is not a collaborator. */
    NONE,
    /** Read-only; user cannot push branches. */
    READ,
    /** Push to branches; cannot merge to a protected default branch. */
    WRITE,
    /** Full control — can push, merge, change branch protection rules. */
    ADMIN
}
