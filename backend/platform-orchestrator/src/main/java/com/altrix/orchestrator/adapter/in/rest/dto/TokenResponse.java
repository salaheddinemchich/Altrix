package com.altrix.orchestrator.adapter.in.rest.dto;

/**
 * Response body for token issuance and refresh endpoints.
 *
 * <p>The refresh token is NOT included here — it is sent as an HttpOnly cookie
 * by the success handler / refresh endpoint. Returning it in the body would
 * make it accessible to JavaScript and defeat the XSS protection.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        int expiresInSeconds,
        String role
) {
    public static TokenResponse of(String accessToken, int expiresInMinutes, String role) {
        return new TokenResponse(accessToken, "Bearer", expiresInMinutes * 60, role);
    }
}
