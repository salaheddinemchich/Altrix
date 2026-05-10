package com.altrix.orchestrator.domain.model.user;

/**
 * Application-level roles for RBAC.
 *
 * <p>Roles follow the Spring Security {@code ROLE_} prefix convention so they map
 * directly to {@code hasRole('ADMIN')} / {@code hasAuthority('ROLE_ADMIN')} in
 * {@code @PreAuthorize} expressions without extra mapping.
 */
public enum UserRole {
    ROLE_USER,
    ROLE_ADMIN,
    ROLE_SUPER_ADMIN;

    /** Returns the role name without the {@code ROLE_} prefix for display/storage. */
    public String shortName() {
        return name().substring(5);
    }
}
