package com.altrix.orchestrator.adapter.in.rest.dto;

/**
 * Response body for {@code GET /api/v1/auth/me}.
 *
 * <p>Derived from the JWT claims already validated by the filter — no DB round-trip
 * needed for this endpoint, which keeps the hot path fast.
 *
 * @param userId    internal Altrix user UUID (JWT {@code sub})
 * @param login     human-readable handle from the provider used to log in
 * @param email     primary email at login time
 * @param role      RBAC role
 * @param provider  OAuth provider used for the current session (e.g. "GITHUB", "GITLAB")
 */
public record UserProfileResponse(
        String userId,
        String login,
        String email,
        String role,
        String provider
) {}
