package com.altrix.orchestrator.adapter.in.rest.dto;

/**
 * Response body for {@code GET /api/v1/auth/me}.
 *
 * <p>Derived from the JWT claims already validated by the filter — no DB round-trip
 * needed for this endpoint, which keeps the hot path fast.
 */
public record UserProfileResponse(
        String githubId,
        String login,
        String email,
        String role
) {}
